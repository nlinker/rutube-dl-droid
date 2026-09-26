package io.github.nlinker.rutubedl

import android.app.Application
import io.github.nlinker.rutubedl.bindings.Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// One Client per process: it owns the HTTP session and cookies, and the
// download service will need the same one the screen used to probe.
class App : Application() {
    val client: Client by lazy { Client() }
    val settings: Settings by lazy { Settings(this) }

    // The queue outlives the DownloadService on purpose: `stopSelf` must not wipe
    // the list of what has already been downloaded.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val queue: DownloadQueue by lazy { DownloadQueue(RealDownloader(this), scope) }

    override fun onCreate() {
        super.onCreate()
        DownloadService.createChannel(this)
    }
}
