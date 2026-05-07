package com.example.autosrtplayer.ui

import androidx.media3.common.MediaItem
import com.example.autosrtplayer.data.favorites.FavoriteItem
import com.example.autosrtplayer.data.playlist.PlaylistEntry
import com.example.autosrtplayer.data.todayhot.TodayHotItem

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
    val todayHotItems: List<TodayHotItem> = emptyList(),
    val isTodayHotLoading: Boolean = false,
    val isTodayHotVisible: Boolean = false,
    val todayHotErrorMessage: String? = null,
    val isFullscreen: Boolean = false,
    val errorMessage: String? = null,
    val errorType: UiErrorType = UiErrorType.None
)
