package io.github.nlinker.rutubedl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import io.github.nlinker.rutubedl.bindings.ProgressListener
import io.github.nlinker.rutubedl.bindings.Quality
import io.github.nlinker.rutubedl.bindings.RutubeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Downloads one video. A foreground service, not a ViewModel coroutine: only a
// service with a visible notification survives the user leaving the screen.
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return stopAndReturn()
        val quality = intent.getStringExtra(EXTRA_QUALITY)?.let(::parseQuality) ?: Quality.Worst
        val folder = intent.getStringExtra(EXTRA_FOLDER)?.let(Uri::parse) ?: return stopAndReturn()

        // One download at a time: the second download will use the same notification and state.
        if (job?.isActive == true) return START_NOT_STICKY

        Downloads.set(DownloadState.Running(title = url, done = 0, total = 0))
        startForegroundWithType(NOTIFICATION_ID, notification(url, 0, 0))
        job = scope.launch {
            // try-finally: the service must stop regardless of the download result.
            try {
                download(url, quality, folder)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun download(url: String, quality: Quality, folder: Uri) {
        val client = (application as App).client
        var document: Uri? = null
        try {
            val download = client.probe(url, quality)
            val info = download.info()
            Downloads.set(DownloadState.Running(info.title, done = 0, total = info.segments.toInt()))
            notify(notification(info.title, 0, info.segments.toInt()))

            document = createDocument(folder, info.fileName)
            // Rust owns the descriptor from here on, so it must be detached.
            val fd = contentResolver.openFileDescriptor(document, "rw")!!.detachFd()
            download.save(fd, cacheDir.absolutePath, Listener(info.title))

            Downloads.set(DownloadState.Done(info.fileName, document))
        } catch (e: RutubeException) {
            Log.w(TAG, "download failed", e)
            discard(document)
            Downloads.set(DownloadState.Failed(e.toString()))
        } catch (e: Exception) {
            // Cancellation lands here too: the half-written document has to go.
            Log.w(TAG, "download stopped", e)
            discard(document)
            if (Downloads.state.value is DownloadState.Running) Downloads.set(DownloadState.Idle)
            throw e
        }
    }

    private fun createDocument(folder: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        return DocumentsContract.createDocument(contentResolver, parent, "video/mp4", name)
            ?: error("could not create $name in the chosen folder")
    }

    private fun discard(document: Uri?) {
        document ?: return
        runCatching { DocumentsContract.deleteDocument(contentResolver, document) }
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    // Called from a tokio worker thread, so it only touches thread-safe state.
    private inner class Listener(private val title: String) : ProgressListener {
        override fun onProgress(done: ULong, total: ULong) {
            Downloads.progress(done.toInt(), total.toInt())
            notify(notification(title, done.toInt(), total.toInt()))
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun notification(title: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_progress, done, total))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    // The service type is required from API 34 on and accepted from API 29.
    private fun startForegroundWithType(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val TAG = "RutubeDL"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "downloads"
        private const val ACTION_CANCEL = "io.github.nlinker.rutubedl.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_FOLDER = "folder"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context, url: String, quality: Quality, folder: Uri) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_QUALITY, formatQuality(quality))
                .putExtra(EXTRA_FOLDER, folder.toString())
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
