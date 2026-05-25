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
import com.example.autosrtplayer.ui.LoadingStage
import com.example.autosrtplayer.ui.SourceWebResolveRequest
import com.example.autosrtplayer.ui.UiErrorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistLoadingState(
    val playlistText: String = "",
    val playlistUrl: String = "",
    val parsedEntry: PlaylistEntry? = null,
    val mediaItem: androidx.media3.common.MediaItem? = null,
    val isLoading: Boolean = false,
    val loadingStage: LoadingStage = LoadingStage.Idle,
    val currentRequestLabel: String? = null,
    val sourceResolveRequest: SourceWebResolveRequest? = null,
    val errorMessage: String? = null,
    val errorType: UiErrorType = UiErrorType.None,
    val lastPlayedMediaUrl: String? = null,
    val playbackPositionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val currentSourceId: String? = null
)

class PlaylistLoadingViewModel(
    private val parser: PlaylistParser = PlaylistParser(),
    private val repository: PlaylistRepository = PlaylistRepository(),
    private val mediaItemBuilder: com.example.autosrtplayer.data.playback.MediaItemBuilder = com.example.autosrtplayer.data.playback.MediaItemBuilder(),
    private val subtitleRepository: SubtitleRepository = SubtitleRepository(),
    private val missavHtmlExtractor: MissavHtmlExtractor = MissavHtmlExtractor(),
    private val missavPlaylistBuilder: MissavPlaylistBuilder = MissavPlaylistBuilder()
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistLoadingState())
    val state: StateFlow<PlaylistLoadingState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var sourceResolveRequestCounter: Long = 0

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun loadFromUrl(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            _state.update {
                it.copy(
                    errorMessage = "請先輸入 M3U8 網址",
                    errorType = UiErrorType.Validation
                )
            }
            return
        }

        _state.update {
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
                _state.update {
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
                _state.update {
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
            _state.update {
                it.copy(
                    errorMessage = "請先貼上 M3U 內容",
                    errorType = UiErrorType.Validation
                )
            }
            return
        }

        _state.update {
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
            _state.update {
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
        _state.update {
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
                _state.update {
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
        _state.update {
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
        val currentState = _state.value
        val request = currentState.sourceResolveRequest ?: return
        if (request.requestId != requestId) return

        val extracted = runCatching { missavHtmlExtractor.extract(html) }.getOrElse { error ->
            _state.update {
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

        _state.update {
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
        val currentState = _state.value
        val request = currentState.sourceResolveRequest ?: return
        if (request.requestId != requestId) return

        _state.update {
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
            _state.update {
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
            _state.update {
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
        _state.update { it.copy(playlistText = value) }
    }

    fun updatePlaylistUrl(value: String) {
        _state.update { it.copy(playlistUrl = value) }
    }

    fun updatePlaybackPosition(positionMs: Long, playWhenReady: Boolean) {
        _state.update {
            it.copy(
                playbackPositionMs = positionMs,
                playWhenReady = playWhenReady
            )
        }
    }

    fun updateLastPlayedMediaUrl(mediaUrl: String?) {
        _state.update { it.copy(lastPlayedMediaUrl = mediaUrl) }
    }
}
