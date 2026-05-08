package com.example.autosrtplayer.data.playback

import android.graphics.Bitmap

data class VideoThumbnailKey(
    val mediaUrl: String,
    val durationMs: Long,
    val userAgent: String?,
    val referrer: String?
)

data class VideoFrameThumbnail(
    val timeMs: Long,
    val bitmap: Bitmap?
)

data class VideoThumbnailState(
    val key: VideoThumbnailKey? = null,
    val isLoading: Boolean = false,
    val thumbnails: List<VideoFrameThumbnail> = emptyList(),
    val errorMessage: String? = null
)
