package com.example.autosrtplayer.ui.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.autosrtplayer.data.favorites.FavoriteItem
import com.example.autosrtplayer.data.playlist.PlaylistEntry
import com.example.autosrtplayer.data.playback.PlayerFactory
import com.example.autosrtplayer.data.todayhot.TodayHotRepository
import com.example.autosrtplayer.ui.PlaybackEvent
import com.example.autosrtplayer.ui.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val playerFactory: PlayerFactory,
    private val todayHotRepository: TodayHotRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var settingsPrefs: SharedPreferences? = null
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var activePlaybackConfig: ActivePlaybackConfig? = null
    private var autoFullscreenPending: Boolean = false

    companion object {
        private const val PrefsName = "autosrt_player_settings"
        private const val KeySourcePrefix = "source_prefix"
        private const val KeyStartupDestination = "startup_destination"
        private const val KeyScreenOrientationMode = "screen_orientation_mode"
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (settingsPrefs == null) {
            settingsPrefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            val sourcePrefix = settingsPrefs?.getString(KeySourcePrefix, "").orEmpty()
            val startupDestination = settingsPrefs
                ?.getString(KeyStartupDestination, null)
                .toStartupDestination()
            val screenOrientationMode = settingsPrefs
                ?.getString(KeyScreenOrientationMode, null)
                .toScreenOrientationMode()
            _uiState.update {
                it.copy(
                    sourcePrefix = sourcePrefix,
                    startupDestination = startupDestination,
                    screenOrientationMode = screenOrientationMode
                )
            }
            when (startupDestination) {
                com.example.autosrtplayer.ui.StartupDestination.TodayHot -> openTodayHot()
                com.example.autosrtplayer.ui.StartupDestination.Favorites -> openFavorites()
                com.example.autosrtplayer.ui.StartupDestination.Player -> Unit
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

        val newPlayer = buildPlayer(context)
        player = newPlayer
        attachPlayerListener(newPlayer)
        syncPlayerWithState(newPlayer)
        return newPlayer
    }

    fun handleEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.RequestFullscreen -> setFullscreen(true)
            is PlaybackEvent.RequestPortrait -> setFullscreen(false)
            is PlaybackEvent.RequestLandscape -> setFullscreen(false)
            is PlaybackEvent.SetPlaybackSpeed -> setPlaybackSpeed(event.speed)
            is PlaybackEvent.ToggleFavorite -> Unit
        }
    }

    fun onSourceIdChange(value: String) {
        _uiState.update { it.copy(sourceId = value) }
    }

    fun onSourcePrefixChange(value: String) {
        _uiState.update { it.copy(sourcePrefix = value) }
    }

    fun saveSourcePrefix() {
        val context = appContext ?: return
        val sourcePrefix = _uiState.value.sourcePrefix.trim()
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeySourcePrefix, sourcePrefix)
            .apply()
        _uiState.update { it.copy(sourcePrefix = sourcePrefix) }
    }

    fun setCurrentSourceId(currentSourceId: String?) {
        _uiState.update { it.copy(currentSourceId = currentSourceId) }
    }

    fun setPlaybackContent(parsedEntry: PlaylistEntry?, mediaItem: MediaItem?) {
        val hadPlaybackContent = _uiState.value.parsedEntry != null || _uiState.value.mediaItem != null
        _uiState.update {
            it.copy(
                parsedEntry = parsedEntry,
                mediaItem = mediaItem
            )
        }

        if (parsedEntry == null || mediaItem == null) {
            if (hadPlaybackContent) {
                activePlaybackConfig = null
                autoFullscreenPending = false
                player?.stop()
            }
            return
        }

        player?.let(::syncPlayerWithState)
    }

    fun openPlayer() {
        _uiState.update { it.copy(activePanel = com.example.autosrtplayer.ui.PlayerPanel.Player, isFullscreen = true) }
    }

    fun openTodayHot() {
        _uiState.update { it.copy(activePanel = com.example.autosrtplayer.ui.PlayerPanel.TodayHot, isFullscreen = false) }
        loadTodayHot()
    }

    fun openFavorites() {
        _uiState.update { it.copy(activePanel = com.example.autosrtplayer.ui.PlayerPanel.Favorites, isFullscreen = false) }
    }

    fun openSettings() {
        _uiState.update { it.copy(activePanel = com.example.autosrtplayer.ui.PlayerPanel.Settings, isFullscreen = false) }
    }

    fun setStartupDestination(destination: com.example.autosrtplayer.ui.StartupDestination) {
        settingsPrefs?.edit()?.putString(KeyStartupDestination, destination.name)?.apply()
        _uiState.update { it.copy(startupDestination = destination) }
    }

    fun toggleScreenOrientationMode() {
        val nextMode = when (_uiState.value.screenOrientationMode) {
            com.example.autosrtplayer.ui.ScreenOrientationMode.Auto -> com.example.autosrtplayer.ui.ScreenOrientationMode.Portrait
            com.example.autosrtplayer.ui.ScreenOrientationMode.Portrait -> com.example.autosrtplayer.ui.ScreenOrientationMode.Landscape
            com.example.autosrtplayer.ui.ScreenOrientationMode.Landscape -> com.example.autosrtplayer.ui.ScreenOrientationMode.Auto
        }
        settingsPrefs?.edit()?.putString(KeyScreenOrientationMode, nextMode.name)?.apply()
        _uiState.update { it.copy(screenOrientationMode = nextMode) }
    }

    fun setFullscreen(isFullscreen: Boolean) {
        val wasFullscreen = _uiState.value.isFullscreen
        _uiState.update {
            it.copy(
                isFullscreen = isFullscreen,
                activePanel = if (isFullscreen) com.example.autosrtplayer.ui.PlayerPanel.Player else it.activePanel
            )
        }
        if (wasFullscreen && !isFullscreen) {
            player?.pause()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
        player?.setPlaybackSpeed(speed)
    }

    fun loadTodayHot() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTodayHotLoading = true, todayHotErrorMessage = null) }
            try {
                val feed = todayHotRepository.loadTodayHot()
                _uiState.update {
                    it.copy(
                        todayHotItems = feed.items,
                        isTodayHotLoading = false,
                        todayHotErrorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTodayHotLoading = false,
                        todayHotErrorMessage = e.message ?: "載入今日熱門失敗"
                    )
                }
            }
        }
    }

    fun toggleCurrentFavorite() {
        val id = _uiState.value.currentSourceId?.trim().orEmpty()
        if (id.isBlank()) return
        val updatedItems = toggleFavoriteInList(id, _uiState.value.favoriteItems)
        _uiState.update { it.copy(favoriteItems = updatedItems, isCurrentFavorite = !isFavorite(updatedItems, id)) }
    }

    fun updatePlaybackPosition(positionMs: Long, playWhenReady: Boolean) {
        _uiState.update {
            it.copy(
                playbackPositionMs = positionMs,
                playWhenReady = playWhenReady
            )
        }
    }

    fun updateIsPlaying(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
    }

    fun updateLastPlayedMediaUrl(mediaUrl: String?) {
        _uiState.update { it.copy(lastPlayedMediaUrl = mediaUrl) }
    }

    override fun onCleared() {
        persistPlaybackState()
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        player = null
        super.onCleared()
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val state = _uiState.value
        val entry = state.parsedEntry ?: return ExoPlayer.Builder(context).build()
        return playerFactory.create(
            context = context,
            userAgent = entry.userAgent,
            referrer = entry.referrer
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
        val state = _uiState.value
        val entry = state.parsedEntry ?: return
        val mediaItem = state.mediaItem ?: return
        val desiredConfig = ActivePlaybackConfig(
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

    private fun recreatePlayer(config: ActivePlaybackConfig): ExoPlayer {
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

    private fun toggleFavoriteInList(id: String, items: List<FavoriteItem>): List<FavoriteItem> {
        val normalized = id.lowercase()
        val updated = items.toMutableList()
        val existingIndex = updated.indexOfFirst { it.id.lowercase() == normalized }
        if (existingIndex >= 0) {
            updated.removeAt(existingIndex)
        } else {
            updated.add(0, FavoriteItem(id = id))
        }
        return updated
    }

    private fun isFavorite(items: List<FavoriteItem>, id: String): Boolean {
        return items.any { it.id.equals(id, ignoreCase = true) }
    }

    private data class ActivePlaybackConfig(
        val mediaUrl: String,
        val userAgent: String?,
        val referrer: String?
    )
}

private fun String?.toStartupDestination(): com.example.autosrtplayer.ui.StartupDestination {
    return runCatching {
        com.example.autosrtplayer.ui.StartupDestination.valueOf(this.orEmpty())
    }.getOrDefault(com.example.autosrtplayer.ui.StartupDestination.Player)
}

private fun String?.toScreenOrientationMode(): com.example.autosrtplayer.ui.ScreenOrientationMode {
    return runCatching {
        com.example.autosrtplayer.ui.ScreenOrientationMode.valueOf(this.orEmpty())
    }.getOrDefault(com.example.autosrtplayer.ui.ScreenOrientationMode.Auto)
}
