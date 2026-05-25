package com.example.autosrtplayer.ui

import androidx.media3.common.MediaItem
import com.example.autosrtplayer.data.favorites.FavoriteItem
import com.example.autosrtplayer.data.playlist.PlaylistEntry
import com.example.autosrtplayer.data.todayhot.TodayHotItem

enum class PlayerPanel {
    Player,
    TodayHot,
    Favorites,
    Settings
}

data class PlaybackUiState(
    val parsedEntry: PlaylistEntry? = null,
    val mediaItem: MediaItem? = null,
    val lastPlayedMediaUrl: String? = null,
    val playbackPositionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val playbackSpeed: Float = 1f,
    val isFullscreen: Boolean = true,
    val activePanel: PlayerPanel = PlayerPanel.Player,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentSourceId: String? = null,
    val isCurrentFavorite: Boolean = false,
    val favoriteItems: List<FavoriteItem> = emptyList(),
    val todayHotItems: List<TodayHotItem> = emptyList(),
    val isTodayHotLoading: Boolean = false,
    val todayHotErrorMessage: String? = null,
    val startupDestination: StartupDestination = StartupDestination.Player,
    val screenOrientationMode: ScreenOrientationMode = ScreenOrientationMode.Auto,
    val sourceId: String = "",
    val sourcePrefix: String = "",
    val currentRequestLabel: String? = null,
    val errorMessage: String? = null,
    val errorType: UiErrorType = UiErrorType.None
)
