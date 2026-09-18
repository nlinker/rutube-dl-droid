package io.github.nlinker.rutubedl

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nlinker.rutubedl.bindings.Quality
import io.github.nlinker.rutubedl.bindings.RutubeException
import io.github.nlinker.rutubedl.bindings.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProbeState {
    data object Idle : ProbeState
    data object Loading : ProbeState
    data class Done(val info: VideoInfo) : ProbeState
    data class Failed(val message: String) : ProbeState
}

data class MainUiState(
    val url: String = "",
    val quality: Quality = Quality.Worst,
    val probe: ProbeState = ProbeState.Idle,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val client = (app as App).client

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    fun setUrl(url: String) = _state.update { it.copy(url = url) }

    fun setQuality(quality: Quality) = _state.update { it.copy(quality = quality) }

    fun probe() {
        val current = _state.value
        if (current.url.isBlank()) return
        _state.update { it.copy(probe = ProbeState.Loading) }
        viewModelScope.launch {
            val result = try {
                ProbeState.Done(client.probe(current.url.trim(), current.quality).info())
            } catch (e: RutubeException) {
                Log.w(TAG, "probe failed", e)
                ProbeState.Failed(e.toString())
            }
            _state.update { it.copy(probe = result) }
        }
    }

    private companion object {
        const val TAG = "RutubeDL"
    }
}
