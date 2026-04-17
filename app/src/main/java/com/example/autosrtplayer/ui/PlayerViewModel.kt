package com.example.autosrtplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autosrtplayer.data.playback.MediaItemBuilder
import com.example.autosrtplayer.data.playlist.PlaylistParser
import com.example.autosrtplayer.data.playlist.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val parser: PlaylistParser = PlaylistParser(),
    private val repository: PlaylistRepository = PlaylistRepository(),
    private val mediaItemBuilder: MediaItemBuilder = MediaItemBuilder()
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun onPlaylistTextChange(value: String) {
        _uiState.update { it.copy(playlistText = value) }
    }

    fun onPlaylistUrlChange(value: String) {
        _uiState.update { it.copy(playlistUrl = value) }
    }

    fun loadFromText() {
        val content = uiState.value.playlistText.trim()
        if (content.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入 playlist 內容") }
            return
        }
        parseAndBuild(content)
    }

    fun loadFromUrl() {
        val url = uiState.value.playlistUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入 playlist 網址") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                repository.loadFromUrl(url)
            }.onSuccess { content ->
                _uiState.update { current -> current.copy(playlistText = content) }
                parseAndBuild(content, url)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "讀取 playlist 失敗"
                    )
                }
            }
        }
    }

    fun setFullscreen(isFullscreen: Boolean) {
        _uiState.update { it.copy(isFullscreen = isFullscreen) }
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    private fun parseAndBuild(content: String, playlistUrl: String? = null) {
        runCatching {
            val entry = parser.parse(content, playlistUrl)
            entry to mediaItemBuilder.build(entry)
        }.onSuccess { (entry, mediaItem) ->
            _uiState.update {
                it.copy(
                    parsedEntry = entry,
                    mediaItem = mediaItem,
                    isLoading = false,
                    errorMessage = null
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    parsedEntry = null,
                    mediaItem = null,
                    isLoading = false,
                    errorMessage = error.message ?: "解析 playlist 失敗"
                )
            }
        }
    }
}
