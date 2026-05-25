package com.example.autosrtplayer.ui

sealed class PlaylistEvent {
    data class LoadFromUrl(val url: String) : PlaylistEvent()
    data class LoadFromText(val content: String) : PlaylistEvent()
    data class LoadFromId(val id: String, val sourcePrefix: String) : PlaylistEvent()
    data class OnSourceHtmlResolved(val requestId: Long, val html: String, val userAgent: String, val finalUrl: String) : PlaylistEvent()
    data class OnSourceResolveFailed(val requestId: Long, val message: String) : PlaylistEvent()
    data class UpdatePlaylistText(val value: String) : PlaylistEvent()
    data class UpdatePlaylistUrl(val value: String) : PlaylistEvent()
}
