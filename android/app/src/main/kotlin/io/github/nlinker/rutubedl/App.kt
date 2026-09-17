package io.github.nlinker.rutubedl

import android.app.Application
import io.github.nlinker.rutubedl.bindings.Client

// One Client per process: it owns the HTTP session and cookies, and the
// download service will need the same one the screen used to probe.
class App : Application() {
    val client: Client by lazy { Client() }
}
