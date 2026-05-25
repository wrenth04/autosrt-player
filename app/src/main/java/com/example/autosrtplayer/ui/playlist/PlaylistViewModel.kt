package com.example.autosrtplayer.ui.playlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autosrtplayer.data.playlist.MissavHtmlExtractor
import com.example.autosrtplayer.data.playlist.MissavPlaylistBuilder
import com.example.autosrtplayer.data.playlist.PlaylistEntry
import com.example.autosrtplayer.data.playlist.PlaylistParser
import com.example.autosrtplayer.data.playlist.PlaylistRepository
import com.example.autosrtplayer.data.playlist.SubtitleRepository
import com.example.autosrtplayer.data.playback.MediaItemBuilder
import com.example.autosrtplayer.ui.LoadingStage
import com.example.autosrtplayer.ui.PlaylistEvent
import com.example.autosrtplayer.ui.PlaylistUiState
import com.example.autosrtplayer.ui.SourceWebResolveRequest
import com.example.autosrtplayer.ui.UiErrorType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val parser: PlaylistParser,
    private val repository: PlaylistRepository,
    private val mediaItemBuilder: MediaItemBuilder,
    private val subtitleRepository: SubtitleRepository,
    private val missavHtmlExtractor: MissavHtmlExtractor,
    private val missavPlaylistBuilder: MissavPlaylistBuilder
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var sourceResolveRequestCounter: Long = 0

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun handleEvent(event: PlaylistEvent) {
        when (event) {
            is PlaylistEvent.LoadFromUrl -> loadFromUrl(event.url)
            is PlaylistEvent.LoadFromText -> loadFromText(event.content)
            is PlaylistEvent.LoadFromId -> loadFromId(event.id, event.sourcePrefix)
            is PlaylistEvent.OnSourceHtmlResolved -> onSourceHtmlResolved(event.requestId, event.html, event.userAgent, event.finalUrl)
            is PlaylistEvent.OnSourceResolveFailed -> onSourceResolveFailed(event.requestId, event.message)
            is PlaylistEvent.UpdatePlaylistText -> updatePlaylistText(event.value)
            is PlaylistEvent.UpdatePlaylistUrl -> updatePlaylistUrl(event.value)
        }
    }

    fun loadFromUrl(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "請先輸入 M3U8 網址",
                    errorType = UiErrorType.Validation
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = LoadingStage.FetchingPlaylist,
                currentRequestLabel = trimmedUrl,
                sourceResolveRequest = null,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }

        viewModelScope.launch {
            try {
                val content = repository.loadFromUrl(trimmedUrl)
                _uiState.update {
                    it.copy(
                        playlistText = content,
                        isLoading = true,
                        loadingStage = LoadingStage.BuildingPlayer,
                        currentRequestLabel = trimmedUrl,
                        sourceResolveRequest = null
                    )
                }
                parseAndBuild(content, trimmedUrl)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingStage = LoadingStage.Idle,
                        currentRequestLabel = null,
                        sourceResolveRequest = null,
                        errorType = UiErrorType.Network,
                        errorMessage = e.message ?: "載入播放清單失敗"
                    )
                }
            }
        }
    }

    fun loadFromText(content: String) {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "請先貼上 M3U 內容",
                    errorType = UiErrorType.Validation
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = LoadingStage.BuildingPlayer,
                currentRequestLabel = "M3U 文字",
                sourceResolveRequest = null,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }

        viewModelScope.launch {
            parseAndBuild(trimmedContent)
        }
    }

    fun loadFromId(id: String, sourcePrefix: String) {
        val trimmedId = id.trim()
        if (trimmedId.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "請先輸入影片 ID",
                    errorType = UiErrorType.Validation
                )
            }
            return
        }

        if (sourcePrefix.isBlank()) {
            startSourceResolve(trimmedId)
            return
        }

        val targetUrl = "$sourcePrefix$trimmedId.m3u8"
        _uiState.update {
            it.copy(
                playlistUrl = targetUrl,
                isLoading = true,
                loadingStage = LoadingStage.ResolvingId,
                currentRequestLabel = "ID: $trimmedId",
                sourceResolveRequest = null,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }

        viewModelScope.launch {
            try {
                val content = repository.loadFromUrl(targetUrl)
                _uiState.update {
                    it.copy(
                        playlistText = content,
                        isLoading = true,
                        loadingStage = LoadingStage.BuildingPlayer,
                        currentRequestLabel = "ID: $trimmedId",
                        sourceResolveRequest = null
                    )
                }
                parseAndBuild(content, targetUrl, trimmedId)
            } catch (e: Exception) {
                startSourceResolve(trimmedId)
            }
        }
    }

    private fun startSourceResolve(id: String) {
        val requestId = ++sourceResolveRequestCounter
        val resolveUrl = "https://missav.ai/$id"
        _uiState.update {
            it.copy(
                playlistUrl = resolveUrl,
                isLoading = true,
                loadingStage = LoadingStage.ResolvingSource,
                currentRequestLabel = "ID: $id",
                sourceResolveRequest = SourceWebResolveRequest(
                    requestId = requestId,
                    id = id,
                    url = resolveUrl
                ),
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
    }

    fun onSourceHtmlResolved(requestId: Long, html: String, userAgent: String, finalUrl: String) {
        val currentState = _uiState.value
        val request = currentState.sourceResolveRequest ?: return
        if (request.requestId != requestId) return

        val extracted = runCatching { missavHtmlExtractor.extract(html) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    sourceResolveRequest = null,
                    errorType = UiErrorType.Parse,
                    errorMessage = error.message ?: "解析失敗"
                )
            }
            return
        }

        val playlistText = missavPlaylistBuilder.build(
            id = request.id,
            detailUrl = finalUrl,
            mediaUrl = extracted.mediaUrl,
            title = extracted.title ?: request.id,
            userAgent = userAgent
        )

        _uiState.update {
            it.copy(
                playlistText = playlistText,
                playlistUrl = finalUrl,
                sourceResolveRequest = null,
                loadingStage = LoadingStage.BuildingPlayer,
                currentRequestLabel = request.id,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }

        viewModelScope.launch {
            parseAndBuild(playlistText, finalUrl, request.id)
        }
    }

    fun onSourceResolveFailed(requestId: Long, message: String) {
        val currentState = _uiState.value
        val request = currentState.sourceResolveRequest ?: return
        if (request.requestId != requestId) return

        _uiState.update {
            it.copy(
                isLoading = false,
                loadingStage = LoadingStage.Idle,
                currentRequestLabel = null,
                sourceResolveRequest = null,
                errorType = UiErrorType.Network,
                errorMessage = message
            )
        }
    }

    private suspend fun parseAndBuild(content: String, playlistUrl: String? = null, sourceId: String? = null) {
        runCatching {
            val entry = parser.parse(content, playlistUrl)
            val subtitleSource = resolveSubtitleSource(entry)
            entry to mediaItemBuilder.build(entry, subtitleSource)
        }.onSuccess { (entry, mediaItem) ->
            _uiState.update {
                it.copy(
                    parsedEntry = entry,
                    mediaItem = mediaItem,
                    currentSourceId = sourceId?.trim()?.takeIf { it.isNotBlank() },
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    sourceResolveRequest = null,
                    errorMessage = null,
                    errorType = UiErrorType.None
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    parsedEntry = null,
                    mediaItem = null,
                    currentSourceId = null,
                    lastPlayedMediaUrl = null,
                    playbackPositionMs = 0L,
                    playWhenReady = true,
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    sourceResolveRequest = null,
                    errorType = UiErrorType.Parse,
                    errorMessage = error.message ?: "解析播放清單失敗"
                )
            }
        }
    }

    private suspend fun resolveSubtitleSource(entry: PlaylistEntry): String? {
        val subtitleUrl = entry.subtitleUrl?.trim().orEmpty()
        if (subtitleUrl.isBlank()) return null

        val context = requireNotNull(appContext)
        val subtitleUri = subtitleRepository.resolveSubtitleUri(
            context = context,
            subtitleUrl = subtitleUrl,
            userAgent = entry.userAgent,
            referrer = entry.referrer
        )
        return subtitleUri.toString()
    }

    fun updatePlaylistText(value: String) {
        _uiState.update { it.copy(playlistText = value) }
    }

    fun updatePlaylistUrl(value: String) {
        _uiState.update { it.copy(playlistUrl = value) }
    }

    fun updatePlaybackPosition(positionMs: Long, playWhenReady: Boolean) {
        _uiState.update {
            it.copy(
                playbackPositionMs = positionMs,
                playWhenReady = playWhenReady
            )
        }
    }

    fun updateLastPlayedMediaUrl(mediaUrl: String?) {
        _uiState.update { it.copy(lastPlayedMediaUrl = mediaUrl) }
    }
}
