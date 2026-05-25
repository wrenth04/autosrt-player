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
