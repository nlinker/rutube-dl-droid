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
import android.os.IBinder
import android.util.Log
import io.github.nlinker.rutubedl.bindings.Quality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.net.toUri

// Downloads one video. A foreground service, not a ViewModel coroutine: only a
// service with a visible notification survives the user leaving the screen.
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloader by lazy { RealDownloader(this) }
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
        startForeground(
            NOTIFICATION_ID,
            notification(url, 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
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
        // id=0 stands in until the queue hands out real ids.
        val task = Task(0, VideoUrl(url), quality, folder?.let { FolderUri(it.toString()) })
        try {
            val finished = downloader.download(task) { title, done, total ->
                Downloads.set(DownloadState.Running(title, done, total))
                notify(notification(title, done, total))
            }
            Downloads.set(DownloadState.Done(finished.name, finished.uri.value.toUri()))
        } catch (e: CancellationException) {
            // The user stopped it: not an error.
            Downloads.set(DownloadState.Idle)
            throw e
        } catch (e: Exception) {
            // RutubeException from Rust or anything else: never let it out of the
            // coroutine, an uncaught exception here takes the whole process down.
            Log.w(TAG, "download failed", e)
            Downloads.set(DownloadState.Failed(e.message ?: e.toString()))
        }
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
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
            .addAction(
                Notification.Action.Builder(null, getString(R.string.cancel), cancel).build()
            )
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
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun start(context: Context, url: String, quality: Quality, folder: Uri?) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_QUALITY, formatQuality(quality))
                .putExtra(EXTRA_FOLDER, folder?.toString())
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(
                    ACTION_CANCEL
                )
            )
        }
    }
}
