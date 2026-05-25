package com.example.autosrtplayer.ui

sealed class PlaybackEvent {
    object RequestFullscreen : PlaybackEvent()
    object RequestPortrait : PlaybackEvent()
    object RequestLandscape : PlaybackEvent()
    data class SetPlaybackSpeed(val speed: Float) : PlaybackEvent()
    object ToggleFavorite : PlaybackEvent()
}
