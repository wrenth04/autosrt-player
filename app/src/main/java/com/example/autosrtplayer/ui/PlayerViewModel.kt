package com.example.autosrtplayer.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import android.graphics.Bitmap
import androidx.media3.exoplayer.ExoPlayer
import com.example.autosrtplayer.data.favorites.FavoriteCodec
import com.example.autosrtplayer.data.favorites.FavoriteItem
import com.example.autosrtplayer.data.playback.ExoPlayerFrameGrabber
import com.example.autosrtplayer.data.playback.MediaItemBuilder
import com.example.autosrtplayer.data.playback.PlayerFactory
import com.example.autosrtplayer.data.playback.VideoFrameThumbnail
import com.example.autosrtplayer.data.playback.VideoThumbnailKey
import com.example.autosrtplayer.data.playback.VideoThumbnailRepository
import com.example.autosrtplayer.data.playback.VideoThumbnailState
import com.example.autosrtplayer.data.playlist.MissavHtmlExtractor
import com.example.autosrtplayer.data.playlist.MissavPlaylistBuilder
import com.example.autosrtplayer.data.playlist.PlaylistParser
import com.example.autosrtplayer.data.playlist.PlaylistRepository
import com.example.autosrtplayer.data.playlist.SubtitleRepository
import com.example.autosrtplayer.data.todayhot.TodayHotRepository
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class PlaybackConfig(
    val mediaUrl: String,
    val userAgent: String?,
    val referrer: String?
)

@androidx.media3.common.util.UnstableApi
class PlayerViewModel(
    private val parser: PlaylistParser = PlaylistParser(),
    private val repository: PlaylistRepository = PlaylistRepository(),
    private val mediaItemBuilder: MediaItemBuilder = MediaItemBuilder(),
    private val subtitleRepository: SubtitleRepository = SubtitleRepository(),
    private val todayHotRepository: TodayHotRepository = TodayHotRepository(),
    private val missavHtmlExtractor: MissavHtmlExtractor = MissavHtmlExtractor(),
    private val missavPlaylistBuilder: MissavPlaylistBuilder = MissavPlaylistBuilder(),
    private val playerFactory: PlayerFactory = PlayerFactory(),
    private val videoThumbnailRepository: VideoThumbnailRepository = VideoThumbnailRepository()
) : ViewModel() {
    companion object {
        private const val PrefsName = "autosrt_player_settings"
        private const val KeySourcePrefix = "source_prefix"
        private const val KeyFavoriteItems = "favorite_items"
        private const val KeyStartupDestination = "startup_destination"
        private const val KeyScreenOrientationMode = "screen_orientation_mode"
        private const val MaxThumbnailCacheEntries = 3
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var settingsPrefs: SharedPreferences? = null
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var activePlaybackConfig: PlaybackConfig? = null
    private var autoFullscreenPending: Boolean = false
    private var sourceResolveRequestCounter: Long = 0
    private var thumbnailJob: Job? = null
    private val thumbnailCache = object : LinkedHashMap<VideoThumbnailKey, List<VideoFrameThumbnail>>(MaxThumbnailCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VideoThumbnailKey, List<VideoFrameThumbnail>>): Boolean {
            return size > MaxThumbnailCacheEntries
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (settingsPrefs == null) {
            settingsPrefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            val sourcePrefix = settingsPrefs?.getString(KeySourcePrefix, "").orEmpty()
            val favoriteItems = FavoriteCodec.decode(settingsPrefs?.getString(KeyFavoriteItems, null))
            val startupDestination = settingsPrefs
                ?.getString(KeyStartupDestination, null)
                .toStartupDestination()
            val screenOrientationMode = settingsPrefs
                ?.getString(KeyScreenOrientationMode, null)
                .toScreenOrientationMode()
            _uiState.update {
                it.copy(
                    sourcePrefix = sourcePrefix,
                    favoriteItems = favoriteItems,
                    startupDestination = startupDestination,
                    screenOrientationMode = screenOrientationMode
                )
            }
            when (startupDestination) {
                StartupDestination.TodayHot -> loadTodayHot()
                StartupDestination.Favorites -> openFavorites()
                StartupDestination.Player -> Unit
            }
        }
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        initialize(context)
        val existingPlayer = player
        if (existingPlayer != null) {
            syncPlayerWithState(existingPlayer)
            return existingPlayer
        }

        val newPlayer = buildPlayer(requireNotNull(appContext))
        player = newPlayer
        attachPlayerListener(newPlayer)
        syncPlayerWithState(newPlayer)
        return newPlayer
    }

    fun onPlaylistTextChange(value: String) {
        _uiState.update { it.copy(playlistText = value) }
    }

    fun onPlaylistUrlChange(value: String) {
        _uiState.update { it.copy(playlistUrl = value) }
    }

    fun onSourceIdChange(value: String) {
        _uiState.update { it.copy(sourceId = value) }
    }

    fun onSourcePrefixChange(value: String) {
        _uiState.update { it.copy(sourcePrefix = value) }
    }

    fun saveSourcePrefix() {
        val sourcePrefix = uiState.value.sourcePrefix.trim()
        settingsPrefs?.edit()?.putString(KeySourcePrefix, sourcePrefix)?.apply()
        _uiState.update { it.copy(sourcePrefix = sourcePrefix) }
    }

    fun openFavorites() {
        _uiState.update {
            it.copy(
                isFavoritesVisible = true,
                isTodayHotVisible = false,
                isSettingsVisible = false
            )
        }
    }

    fun closeFavorites() {
        _uiState.update { it.copy(isFavoritesVisible = false) }
    }

    fun openSettings() {
        _uiState.update {
            it.copy(
                isSettingsVisible = true,
                isFavoritesVisible = false,
                isTodayHotVisible = false
            )
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsVisible = false) }
    }

    fun setStartupDestination(destination: StartupDestination) {
        settingsPrefs?.edit()?.putString(KeyStartupDestination, destination.name)?.apply()
        _uiState.update { it.copy(startupDestination = destination) }
    }

    fun toggleScreenOrientationMode() {
        val nextMode = when (_uiState.value.screenOrientationMode) {
            ScreenOrientationMode.Auto -> ScreenOrientationMode.Portrait
            ScreenOrientationMode.Portrait -> ScreenOrientationMode.Landscape
            ScreenOrientationMode.Landscape -> ScreenOrientationMode.Auto
        }
        settingsPrefs?.edit()?.putString(KeyScreenOrientationMode, nextMode.name)?.apply()
        _uiState.update { it.copy(screenOrientationMode = nextMode) }
    }

    fun toggleCurrentFavorite() {
        val state = uiState.value
        val id = state.currentSourceId?.trim().orEmpty()
        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "目前影片沒有可儲存的 ID", errorType = UiErrorType.Validation) }
            return
        }

        val normalized = id.lowercase()
        val updated = state.favoriteItems.toMutableList()
        val existingIndex = updated.indexOfFirst { it.id.lowercase() == normalized }
        if (existingIndex >= 0) {
            updated.removeAt(existingIndex)
        } else {
            updated.add(0, FavoriteItem(id = id))
        }
        persistFavoriteItems(updated)
        _uiState.update { it.copy(favoriteItems = updated) }
    }

    fun removeFavorite(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val updated = uiState.value.favoriteItems.filterNot { it.id.equals(normalized, ignoreCase = true) }
        persistFavoriteItems(updated)
        _uiState.update { it.copy(favoriteItems = updated) }
    }

    fun playFavorite(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        _uiState.update { it.copy(sourceId = normalized, isFavoritesVisible = false) }
        loadFromId()
    }

    private fun persistFavoriteItems(items: List<FavoriteItem>) {
        settingsPrefs?.edit()?.putString(KeyFavoriteItems, FavoriteCodec.encode(items))?.apply()
    }

    private fun showPlayerShell() {
        thumbnailJob?.cancel()
        thumbnailJob = null
        _uiState.update {
            it.copy(
                thumbnailState = VideoThumbnailState(),
                isFavoritesVisible = false,
                isTodayHotVisible = false,
                isSettingsVisible = false
            )
        }
    }

    fun loadFromId() {
        val state = uiState.value
        val id = state.sourceId.trim()
        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入影片 ID", errorType = UiErrorType.Validation) }
            return
        }
        showPlayerShell()
        val prefix = state.sourcePrefix.trim()
        if (prefix.isBlank()) {
            startSourceResolve(id)
            return
        }
        val targetUrl = "$prefix$id.m3u8"
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    playlistUrl = targetUrl,
                    isLoading = true,
                    loadingStage = LoadingStage.ResolvingId,
                    currentRequestLabel = "ID: $id",
                    sourceResolveRequest = null,
                    errorMessage = null,
                    errorType = UiErrorType.None
                )
            }

            runCatching {
                repository.loadFromUrl(targetUrl)
            }.onSuccess { content ->
                _uiState.update {
                    it.copy(
                        playlistText = content,
                        isLoading = true,
                        loadingStage = LoadingStage.BuildingPlayer,
                        currentRequestLabel = "ID: $id",
                        sourceResolveRequest = null,
                        errorMessage = null,
                        errorType = UiErrorType.None
                    )
                }
                parseAndBuild(content, targetUrl, id)
            }.onFailure {
                startSourceResolve(id)
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

    fun loadFromSharedUrl(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        showPlayerShell()
        _uiState.update { it.copy(playlistUrl = normalized, sourceResolveRequest = null, currentSourceId = null) }
        loadFromUrl(normalized)
    }

    fun loadFromExternalId(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        showPlayerShell()
        _uiState.update { it.copy(sourceId = normalized) }
        loadFromId()
    }

    fun loadTodayHot() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTodayHotLoading = true,
                    isTodayHotVisible = true,
                    isFavoritesVisible = false,
                    isSettingsVisible = false,
                    todayHotErrorMessage = null
                )
            }
            runCatching {
                todayHotRepository.loadTodayHot()
            }.onSuccess { feed ->
                _uiState.update {
                    it.copy(
                        todayHotItems = feed.items,
                        isTodayHotLoading = false,
                        isTodayHotVisible = true,
                        todayHotErrorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isTodayHotLoading = false,
                        isTodayHotVisible = true,
                        todayHotErrorMessage = error.message ?: "載入今日熱門失敗"
                    )
                }
            }
        }
    }

    fun closeTodayHot() {
        _uiState.update { it.copy(isTodayHotVisible = false) }
    }

    fun playTodayHotCode(code: String) {
        val normalized = code.trim()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(errorMessage = "今日熱門代碼無效", errorType = UiErrorType.Validation) }
            return
        }
        _uiState.update { it.copy(sourceId = normalized, isTodayHotVisible = false, isSettingsVisible = false, isFavoritesVisible = false) }
        loadFromId()
    }

    fun loadFromText() {
        val content = uiState.value.playlistText.trim()
        if (content.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先貼上 M3U 內容", errorType = UiErrorType.Validation) }
            return
        }
        showPlayerShell()
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = LoadingStage.BuildingPlayer,
                currentRequestLabel = "M3U 文字",
                sourceResolveRequest = null,
                currentSourceId = null,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
        viewModelScope.launch {
            parseAndBuild(content)
        }
    }

    fun loadFromUrl(targetUrl: String? = null) {
        val url = targetUrl?.trim() ?: uiState.value.playlistUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入 M3U8 網址", errorType = UiErrorType.Validation) }
            return
        }

        showPlayerShell()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingStage = LoadingStage.FetchingPlaylist,
                    currentRequestLabel = url,
                    sourceResolveRequest = null,
                    currentSourceId = null,
                    errorMessage = null,
                    errorType = UiErrorType.None
                )
            }
            runCatching {
                repository.loadFromUrl(url)
            }.onSuccess { content ->
                _uiState.update { current ->
                    current.copy(
                        playlistText = content,
                        isLoading = true,
                        loadingStage = LoadingStage.BuildingPlayer,
                        currentRequestLabel = url,
                        sourceResolveRequest = null
                    )
                }
                parseAndBuild(content, url)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingStage = LoadingStage.Idle,
                        currentRequestLabel = null,
                        sourceResolveRequest = null,
                        errorType = UiErrorType.Network,
                        errorMessage = error.message ?: "載入播放清單失敗"
                    )
                }
            }
        }
    }

    fun onSourceHtmlResolved(requestId: Long, html: String, userAgent: String, finalUrl: String) {
        val state = uiState.value
        val request = state.sourceResolveRequest ?: return
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
        val state = uiState.value
        val request = state.sourceResolveRequest ?: return
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

    fun setFullscreen(isFullscreen: Boolean) {
        val wasFullscreen = _uiState.value.isFullscreen
        _uiState.update { it.copy(isFullscreen = isFullscreen) }
        if (wasFullscreen && !isFullscreen) {
            player?.pause()
        }
    }

    fun toggleFullscreen() {
        val wasFullscreen = _uiState.value.isFullscreen
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
        if (wasFullscreen) {
            player?.pause()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
        player?.setPlaybackSpeed(speed)
    }

    fun loadPausedThumbnailsIfNeeded(durationMs: Long) {
        val state = uiState.value
        val entry = state.parsedEntry ?: return
        if (durationMs <= 0L) return

        val mediaUrl = entry.mediaUrl.trim()
        if (mediaUrl.isBlank()) return

        val key = VideoThumbnailKey(
            mediaUrl = mediaUrl,
            durationMs = durationMs,
            userAgent = entry.userAgent,
            referrer = entry.referrer
        )

        val currentState = state.thumbnailState
        if (currentState.key == key && (currentState.isLoading || currentState.thumbnails.isNotEmpty())) {
            return
        }

        thumbnailCache[key]?.let { cached ->
            _uiState.update { current ->
                if (current.thumbnailState.key == key || current.thumbnailState.key == null) {
                    current.copy(
                        thumbnailState = VideoThumbnailState(
                            key = key,
                            isLoading = false,
                            thumbnails = cached,
                            errorMessage = null
                        )
                    )
                } else {
                    current
                }
            }
            return
        }

        thumbnailJob?.cancel()
        _uiState.update { current ->
            if (current.thumbnailState.key == key && current.thumbnailState.isLoading) {
                current
            } else {
                current.copy(
                    thumbnailState = VideoThumbnailState(
                        key = key,
                        isLoading = true,
                        thumbnails = emptyList(),
                        errorMessage = null
                    )
                )
            }
        }

        thumbnailJob = viewModelScope.launch {
            try {
                val thumbnails = loadThumbnailsWithPlayerThenFallback(key)
                thumbnailCache[key] = thumbnails
                _uiState.update { current ->
                    if (current.thumbnailState.key == key) {
                        current.copy(
                            thumbnailState = VideoThumbnailState(
                                key = key,
                                isLoading = false,
                                thumbnails = thumbnails,
                                errorMessage = null
                            )
                        )
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update { current ->
                    if (current.thumbnailState.key == key) {
                        current.copy(
                            thumbnailState = VideoThumbnailState(
                                key = key,
                                isLoading = false,
                                thumbnails = emptyList(),
                                errorMessage = error.message ?: "縮圖載入失敗"
                            )
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }


    private suspend fun loadThumbnailsWithPlayerThenFallback(key: VideoThumbnailKey): List<VideoFrameThumbnail> {
        val player = player
        val playerThumbnails = if (player != null) {
            ExoPlayerFrameGrabber { timeMs, _ ->
                captureFrameFromHiddenPlayer(player, timeMs)
            }.captureThumbnails(key)
        } else {
            emptyList()
        }

        val successfulPlayerFrames = playerThumbnails.filter { it.bitmap != null }
        if (successfulPlayerFrames.isNotEmpty()) {
            return playerThumbnails
        }

        val fallback = videoThumbnailRepository.loadThumbnailsWithRetrieverFallback(key)
        if (fallback.any { it.bitmap != null }) {
            return fallback
        }

        return if (playerThumbnails.isNotEmpty()) {
            playerThumbnails
        } else {
            listOf(VideoFrameThumbnail(timeMs = 0L, bitmap = null))
        }
    }

    private suspend fun captureFrameFromHiddenPlayer(player: ExoPlayer, timeMs: Long): Bitmap? {
        // Hidden-player thumbnail extraction path: seek with shared data source/headers, then capture render frame.
        // TextureView/ImageReader wiring will return a bitmap when integrated in UI layer.
        player.seekTo(timeMs)
        return null
    }
    override fun onCleared() {
        persistPlaybackState()
        thumbnailJob?.cancel()
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        player = null
        super.onCleared()
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
            player?.let(::syncPlayerWithState)
        }.onFailure { error ->
            activePlaybackConfig = null
            player?.stop()
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

    private suspend fun resolveSubtitleSource(entry: com.example.autosrtplayer.data.playlist.PlaylistEntry): String? {
        val subtitleUrl = entry.subtitleUrl?.trim().orEmpty()
        if (subtitleUrl.isBlank()) {
            return null
        }

        val subtitleUri = subtitleRepository.resolveSubtitleUri(
            context = requireNotNull(appContext),
            subtitleUrl = subtitleUrl,
            userAgent = entry.userAgent,
            referrer = entry.referrer
        )
        return subtitleUri.toString()
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val state = uiState.value
        return playerFactory.create(
            context = context,
            userAgent = state.parsedEntry?.userAgent,
            referrer = state.parsedEntry?.referrer
        )
    }

    private fun attachPlayerListener(player: ExoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && autoFullscreenPending) {
                    autoFullscreenPending = false
                    _uiState.update { state ->
                        if (state.isFullscreen) state else state.copy(isFullscreen = true)
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                persistPlaybackState()
                _uiState.update {
                    it.copy(
                        lastPlayedMediaUrl = activePlaybackConfig?.mediaUrl,
                        playbackPositionMs = player.currentPosition,
                        playWhenReady = player.playWhenReady
                    )
                }
            }
        }
        player.addListener(listener)
        playerListener = listener
    }

    private fun syncPlayerWithState(player: ExoPlayer) {
        val state = uiState.value
        val entry = state.parsedEntry ?: return
        val mediaItem = state.mediaItem ?: return
        val desiredConfig = PlaybackConfig(
            mediaUrl = entry.mediaUrl,
            userAgent = entry.userAgent,
            referrer = entry.referrer
        )
        val currentConfig = activePlaybackConfig

        if (currentConfig == desiredConfig && player.currentMediaItem == mediaItem) {
            return
        }

        if (currentConfig != null && currentConfig != desiredConfig) {
            persistPlaybackState()
        }

        val desiredHasHeaders = !desiredConfig.userAgent.isNullOrBlank() || !desiredConfig.referrer.isNullOrBlank()
        val headersChanged = currentConfig?.userAgent != desiredConfig.userAgent ||
            currentConfig?.referrer != desiredConfig.referrer
        val needsRecreate = if (currentConfig == null) {
            desiredHasHeaders
        } else {
            currentConfig != desiredConfig && headersChanged
        }

        val targetPlayer = if (needsRecreate) {
            recreatePlayer(desiredConfig)
        } else {
            player
        }

        val resumeSameMedia = state.lastPlayedMediaUrl == desiredConfig.mediaUrl
        val startPositionMs = if (resumeSameMedia) state.playbackPositionMs else 0L
        val playWhenReady = if (resumeSameMedia) state.playWhenReady else true

        autoFullscreenPending = true
        activePlaybackConfig = desiredConfig
        targetPlayer.setMediaItem(mediaItem, startPositionMs)
        targetPlayer.prepare()
        targetPlayer.setPlaybackSpeed(state.playbackSpeed)
        targetPlayer.playWhenReady = playWhenReady
        _uiState.update {
            it.copy(
                lastPlayedMediaUrl = desiredConfig.mediaUrl,
                playbackPositionMs = startPositionMs,
                playWhenReady = playWhenReady
            )
        }
    }

    private fun recreatePlayer(config: PlaybackConfig): ExoPlayer {
        val context = requireNotNull(appContext)
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        val newPlayer = playerFactory.create(
            context = context,
            userAgent = config.userAgent,
            referrer = config.referrer
        )
        player = newPlayer
        attachPlayerListener(newPlayer)
        return newPlayer
    }

    private fun persistPlaybackState() {
        val currentPlayer = player ?: return
        val mediaUrl = activePlaybackConfig?.mediaUrl ?: return
        _uiState.update {
            it.copy(
                lastPlayedMediaUrl = mediaUrl,
                playbackPositionMs = currentPlayer.currentPosition,
                playWhenReady = currentPlayer.playWhenReady
            )
        }
    }
}

private fun String?.toStartupDestination(): StartupDestination {
    return runCatching {
        StartupDestination.valueOf(this.orEmpty())
    }.getOrDefault(StartupDestination.Player)
}

private fun String?.toScreenOrientationMode(): ScreenOrientationMode {
    return runCatching {
        ScreenOrientationMode.valueOf(this.orEmpty())
    }.getOrDefault(ScreenOrientationMode.Auto)
}
