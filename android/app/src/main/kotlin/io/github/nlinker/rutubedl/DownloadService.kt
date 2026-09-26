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
import io.github.nlinker.rutubedl.bindings.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Keeps the downloads alive and visible. The work itself belongs to `App.queue`. The queue outlives
// this service. The service adds a foreground notification, which is what stops Android from
// killing the process while a download runs.
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue by lazy { (application as App).queue }
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // No task id is passed with the intent: the button means "stop all the downloads".
            queue.entries.value.filter { it.state is TaskState.Running }.forEach { queue.cancel(it.task.id) }
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return stopAndReturn()
        val quality = intent.getStringExtra(EXTRA_QUALITY)?.let(::parseQuality) ?: Quality.Worst
        // No folder means the system Download collection.
        val folder = intent.getStringExtra(EXTRA_FOLDER)?.let(::FolderUri)

        // The notification has to be pushed within five seconds of startForegroundService, and the
        // title is all we know until the probe comes back.
        startForeground(
            NOTIFICATION_ID,
            notification(url, 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        queue.enqueue(VideoUrl(url), quality, folder)
        watchQueue()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // One collector drives both the notification and the lifetime of the service. Started after
    // the first enqueue, so `first { !isBusy }` cannot see the empty list we began with.
    private fun watchQueue() {
        if (watching) return
        watching = true
        scope.launch {
            queue.entries.collect { entries ->
                val running = entries.firstOrNull { it.state is TaskState.Running }
                if (running == null && !entries.isBusy) {
                    stopSelf()
                    return@collect
                }
                if (running != null) {
                    val state = running.state as TaskState.Running
                    notify(notification(running.title ?: running.task.url.value, state.done, state.total))
                }
            }
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
    }
}
