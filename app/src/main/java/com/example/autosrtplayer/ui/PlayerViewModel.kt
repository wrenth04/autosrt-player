package com.example.autosrtplayer.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.autosrtplayer.data.playback.MediaItemBuilder
import com.example.autosrtplayer.data.playback.PlayerFactory
import com.example.autosrtplayer.data.playlist.PlaylistParser
import com.example.autosrtplayer.data.playlist.PlaylistRepository
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

class PlayerViewModel(
    private val parser: PlaylistParser = PlaylistParser(),
    private val repository: PlaylistRepository = PlaylistRepository(),
    private val mediaItemBuilder: MediaItemBuilder = MediaItemBuilder(),
    private val playerFactory: PlayerFactory = PlayerFactory()
) : ViewModel() {
    companion object {
        private const val PrefsName = "autosrt_player_settings"
        private const val KeySourcePrefix = "source_prefix"
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var settingsPrefs: SharedPreferences? = null
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var activePlaybackConfig: PlaybackConfig? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (settingsPrefs == null) {
            settingsPrefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            val sourcePrefix = settingsPrefs?.getString(KeySourcePrefix, "").orEmpty()
            _uiState.update { it.copy(sourcePrefix = sourcePrefix) }
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

    fun loadFromId() {
        val state = uiState.value
        val id = state.sourceId.trim()
        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入影片 ID", errorType = UiErrorType.Validation) }
            return
        }
        val prefix = state.sourcePrefix.trim()
        if (prefix.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先到進階選項設定來源", errorType = UiErrorType.PrefixMissing) }
            return
        }
        val targetUrl = "$prefix$id.m3u8"
        _uiState.update {
            it.copy(
                playlistUrl = targetUrl,
                isLoading = true,
                loadingStage = LoadingStage.ResolvingId,
                currentRequestLabel = "ID: $id",
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
        loadFromUrl(targetUrl)
    }

    fun loadFromText() {
        val content = uiState.value.playlistText.trim()
        if (content.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先貼上 M3U 內容", errorType = UiErrorType.Validation) }
            return
        }
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = LoadingStage.BuildingPlayer,
                currentRequestLabel = "M3U 文字",
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
        parseAndBuild(content)
    }

    fun loadFromUrl(targetUrl: String? = null) {
        val url = targetUrl?.trim() ?: uiState.value.playlistUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入 M3U8 網址", errorType = UiErrorType.Validation) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingStage = LoadingStage.FetchingPlaylist,
                    currentRequestLabel = url,
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
                        currentRequestLabel = url
                    )
                }
                parseAndBuild(content, url)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingStage = LoadingStage.Idle,
                        currentRequestLabel = null,
                        errorType = UiErrorType.Network,
                        errorMessage = error.message ?: "載入播放清單失敗"
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

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
        player?.setPlaybackSpeed(speed)
    }

    override fun onCleared() {
        persistPlaybackState()
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        player = null
        super.onCleared()
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
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
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
                    lastPlayedMediaUrl = null,
                    playbackPositionMs = 0L,
                    playWhenReady = true,
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    errorType = UiErrorType.Parse,
                    errorMessage = error.message ?: "解析播放清單失敗"
                )
            }
        }
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

        if (currentConfig == desiredConfig && player.currentMediaItem != null) {
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
