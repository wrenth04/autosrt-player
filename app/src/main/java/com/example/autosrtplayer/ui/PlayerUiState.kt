package com.example.autosrtplayer.ui

import androidx.media3.common.MediaItem
import com.example.autosrtplayer.data.favorites.FavoriteItem
import com.example.autosrtplayer.data.playlist.PlaylistEntry
import com.example.autosrtplayer.data.todayhot.TodayHotItem

enum class StartupDestination {
    Player,
    TodayHot,
    Favorites
}

data class SourceWebResolveRequest(
    val requestId: Long,
    val id: String,
    val url: String
)

enum class LoadingStage {
    Idle,
    ResolvingId,
    FetchingPlaylist,
    ResolvingSource,
    BuildingPlayer
}

enum class UiErrorType {
    None,
    Validation,
    PrefixMissing,
    Network,
    Parse,
    Unknown
}

enum class ScreenOrientationMode {
    Auto,
    Portrait,
    Landscape
}

data class PlayerUiState(
    val sourceId: String = "",
    val currentSourceId: String? = null,
    val sourcePrefix: String = "",
    val playlistText: String = "",
    val playlistUrl: String = "",
    val parsedEntry: PlaylistEntry? = null,
    val mediaItem: MediaItem? = null,
    val lastPlayedMediaUrl: String? = null,
    val playbackPositionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val playbackSpeed: Float = 1f,
    val isLoading: Boolean = false,
    val loadingStage: LoadingStage = LoadingStage.Idle,
    val currentRequestLabel: String? = null,
    val sourceResolveRequest: SourceWebResolveRequest? = null,
    val favoriteItems: List<FavoriteItem> = emptyList(),
    val isFavoritesVisible: Boolean = false,
    val todayHotItems: List<TodayHotItem> = emptyList(),
    val isTodayHotLoading: Boolean = false,
    val isTodayHotVisible: Boolean = false,
    val todayHotErrorMessage: String? = null,
    val isSettingsVisible: Boolean = false,
    val startupDestination: StartupDestination = StartupDestination.Player,
    val isFullscreen: Boolean = true,
    val screenOrientationMode: ScreenOrientationMode = ScreenOrientationMode.Auto,
    val vrConfig: VrPlaybackConfig = VrPlaybackConfig(),
    val vrViewAngles: VrViewAngles = VrViewAngles(),
    val errorMessage: String? = null,
    val errorType: UiErrorType = UiErrorType.None,
    val favoriteImportMessage: String? = null,
    val favoriteExportMessage: String? = null
)
