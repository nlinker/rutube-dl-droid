package io.github.nlinker.rutubedl

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nlinker.rutubedl.bindings.Quality
import io.github.nlinker.rutubedl.bindings.RutubeException
import io.github.nlinker.rutubedl.bindings.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProbeState {
    data object Idle : ProbeState
    data object Loading : ProbeState
    data class Done(val info: VideoInfo) : ProbeState
    data class Failed(val message: String) : ProbeState
}

data class MainUiState(
    val probe: ProbeState = ProbeState.Idle,
    val showSettings: Boolean = false,
    // The last picked folder came from a provider we cannot seek in.
    val folderRejected: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val client = (app as App).client
    private val settings = (app as App).settings
    private val resolver = app.contentResolver

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    // Null until DataStore has been read once.
    val settingsState: StateFlow<AppSettings?> =
        settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The link field's own state: text plus selection.
    val url = TextFieldState()

    fun setUrl(url: String) = this.url.setTextAndPlaceCursorAtEnd(url)

    // String? (null or non-empty string) is better than String here, because
    // the compiler helps to ensure a non-empty link in usages like `client.probe`
    private val link: String? get() = url.text.trim().toString().ifBlank { null }

    fun setChoice(choice: Choice) {
        viewModelScope.launch { settings.setChoice(choice) }
    }

    fun setHeight(choice: Choice, height: UInt) {
        viewModelScope.launch { settings.setHeight(choice, height) }
    }

    fun resetHeights() {
        viewModelScope.launch { settings.resetHeights() }
    }

    fun openSettings() = _state.update { it.copy(showSettings = true) }

    fun closeSettings() = _state.update { it.copy(showSettings = false) }

    // Result of ACTION_OPEN_DOCUMENT_TREE; null when the user backed out.
    fun pickFolder(uri: Uri?) {
        if (uri == null) return
        // Only the local provider gives seekable files; the remux needs to seek.
        if (uri.authority != LOCAL_AUTHORITY) {
            _state.update { it.copy(folderRejected = true) }
            return
        }
        // Keep the grant across reboots, otherwise the Uri dies with the picker activity.
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        _state.update { it.copy(folderRejected = false) }
        viewModelScope.launch { settings.setFolder(uri) }
    }

    // Back to the system Download collection.
    fun resetFolder() {
        _state.update { it.copy(folderRejected = false) }
        viewModelScope.launch { settings.clearFolder() }
    }

    val downloadState: StateFlow<DownloadState> = Downloads.state

    fun startDownload(context: Context) {
        val current = _state.value
        val prefs = settingsState.value ?: return
        val link = link ?: return
        // Exact height of the selected row after a probe, the preference before one.
        val quality = (current.probe as? ProbeState.Done)
            ?.let { prefs.resolve(prefs.choice, it.info.variants) }
            ?.let { Quality.Height(it.height) }
            ?: Quality.AtMost(prefs.preferredHeight)
        DownloadService.start(context, link, quality, prefs.folder)
    }

    fun cancelDownload(context: Context) = DownloadService.cancel(context)

    fun probe() {
        val prefs = settingsState.value ?: return
        val link = link ?: return
        _state.update { it.copy(probe = ProbeState.Loading) }
        viewModelScope.launch {
            val result = try {
                ProbeState.Done(client.probe(link, Quality.AtMost(prefs.preferredHeight)).info())
            } catch (e: RutubeException) {
                Log.w(TAG, "probe failed", e)
                ProbeState.Failed(e.toString())
            }
            _state.update { it.copy(probe = result) }
        }
    }

    private companion object {
        const val TAG = "RutubeDL"
        const val LOCAL_AUTHORITY = "com.android.externalstorage.documents"
    }
}
