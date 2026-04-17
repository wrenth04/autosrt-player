package com.example.autosrtplayer.ui

import androidx.media3.common.MediaItem
import com.example.autosrtplayer.data.playlist.PlaylistEntry

data class PlayerUiState(
    val playlistText: String = "",
    val playlistUrl: String = "",
    val parsedEntry: PlaylistEntry? = null,
    val mediaItem: MediaItem? = null,
    val isLoading: Boolean = false,
    val isFullscreen: Boolean = false,
    val errorMessage: String? = null
)
