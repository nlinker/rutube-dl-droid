package io.github.nlinker.rutubedl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import io.github.nlinker.rutubedl.bindings.ProgressListener
import io.github.nlinker.rutubedl.bindings.Quality
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
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
        // No folder means the system Download collection.
        val folder = intent.getStringExtra(EXTRA_FOLDER)?.let(Uri::parse)

        // One download at a time: the second download will use the same notification and state.
        if (job?.isActive == true) return START_NOT_STICKY

        Downloads.set(DownloadState.Running(title = url, done = 0, total = 0))
        startForeground(NOTIFICATION_ID, notification(url, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    private suspend fun download(url: String, quality: Quality, folder: Uri?) {
        val client = (application as App).client
        var target: Target? = null
        try {
            val download = client.probe(url, quality)
            val info = download.info()
            Downloads.set(DownloadState.Running(info.title, done = 0, total = info.segments.toInt()))
            notify(notification(info.title, 0, info.segments.toInt()))

            target = if (folder == null) createInDownloads(info.fileName) else createDocument(folder, info.fileName)
            // Rust owns the descriptor from here on, so it must be detached.
            val fd = contentResolver.openFileDescriptor(target.uri, "rw")!!.detachFd()
            download.save(fd, cacheDir.absolutePath, Listener(info.title))
            target.commit()

            Downloads.set(DownloadState.Done(info.fileName, target.uri))
        } catch (e: CancellationException) {
            // The user stopped it: not an error, but the half-written file has to go.
            target?.discard()
            Downloads.set(DownloadState.Idle)
            throw e
        } catch (e: FileNotFoundException) {
            // The chosen folder is gone; forget it and fall back to Download next time.
            Log.w(TAG, "download folder is missing", e)
            (application as App).settings.clearFolder()
            Downloads.set(DownloadState.Failed(getString(R.string.folder_missing)))
        } catch (e: Exception) {
            // RutubeException from Rust or anything else: never let it out of the
            // coroutine, an uncaught exception here takes the whole process down.
            Log.w(TAG, "download failed", e)
            target?.discard()
            Downloads.set(DownloadState.Failed(e.message ?: e.toString()))
        }
    }

    // The wrapper around the file being written: a MediaStore row or a SAF document.
    private inner class Target(val uri: Uri, private val pending: Boolean) {
        // MediaStore hides a pending row from other apps until the flag is cleared.
        fun commit() {
            if (pending) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            }
        }

        fun discard() = runCatching {
            if (pending) contentResolver.delete(uri, null, null) else DocumentsContract.deleteDocument(contentResolver, uri)
        }
    }

    // The default: the system Download collection, no folder picker and no permission.
    private fun createInDownloads(name: String): Target {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create $name in Download")
        return Target(uri, pending = true)
    }

    // A folder the user picked through SAF. The tree Uri names a grant, not a
    // directory; the document Uri of its root is what createDocument wants.
    private fun createDocument(folder: Uri, name: String): Target {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val uri = DocumentsContract.createDocument(contentResolver, parent, "video/mp4", name)
            ?: error("could not create $name in the chosen folder")
        return Target(uri, pending = false)
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

        fun start(context: Context, url: String, quality: Quality, folder: Uri?) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_QUALITY, formatQuality(quality))
                .putExtra(EXTRA_FOLDER, folder?.toString())
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
