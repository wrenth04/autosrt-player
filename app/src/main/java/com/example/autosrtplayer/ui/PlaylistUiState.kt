package com.example.autosrtplayer.ui

import androidx.media3.common.MediaItem
import com.example.autosrtplayer.data.playlist.PlaylistEntry

data class PlaylistUiState(
    val playlistText: String = "",
    val playlistUrl: String = "",
    val parsedEntry: PlaylistEntry? = null,
    val mediaItem: MediaItem? = null,
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
