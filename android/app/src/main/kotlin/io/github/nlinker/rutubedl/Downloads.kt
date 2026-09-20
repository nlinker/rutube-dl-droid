package io.github.nlinker.rutubedl

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface DownloadState {
    data object Idle : DownloadState
    // `total` is 0 until the probe comes back with a segment count.
    data class Running(val title: String, val done: Int, val total: Int) : DownloadState
    data class Done(val name: String, val uri: Uri) : DownloadState
    data class Failed(val message: String) : DownloadState
}

// The service writes, the screen reads. A process-wide singleton rather than a
// bound service: both live in the same process, and a Binder would buy nothing.
object Downloads {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    fun set(state: DownloadState) {
        _state.value = state
    }

    fun progress(done: Int, total: Int) {
        val running = _state.value as? DownloadState.Running ?: return
        _state.value = running.copy(done = done, total = total)
    }
}
