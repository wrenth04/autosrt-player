package com.example.autosrtplayer.data.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoThumbnailRepository {
    suspend fun loadThumbnails(
        request: VideoThumbnailKey,
        count: Int = DefaultThumbnailCount,
        targetWidth: Int = DefaultThumbnailWidth,
        targetHeight: Int = DefaultThumbnailHeight
    ): List<VideoFrameThumbnail> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val headers = buildMap {
                request.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                request.referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            }
            if (headers.isEmpty()) {
                retriever.setDataSource(request.mediaUrl)
            } else {
                retriever.setDataSource(request.mediaUrl, headers)
            }

            buildThumbnailTimes(request.durationMs, count).map { timeMs ->
                VideoFrameThumbnail(
                    timeMs = timeMs,
                    bitmap = extractFrame(retriever, timeMs, targetWidth, targetHeight)
                )
            }
        } finally {
            retriever.release()
        }
    }

    private fun buildThumbnailTimes(durationMs: Long, count: Int): List<Long> {
        val safeCount = count.coerceAtLeast(1)
        val safeDuration = durationMs.coerceAtLeast(1L)
        return List(safeCount) { index ->
            safeDuration * (index + 1) / (safeCount + 1)
        }
    }

    private fun extractFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val timeUs = timeMs * 1_000L
        val frame = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull() ?: runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null

        if (frame.width <= targetWidth && frame.height <= targetHeight) {
            return frame
        }

        val scale = minOf(
            targetWidth.toFloat() / frame.width.toFloat(),
            targetHeight.toFloat() / frame.height.toFloat()
        ).coerceAtMost(1f)
        if (scale >= 1f) return frame

        val scaledWidth = (frame.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (frame.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true)
    }

    companion object {
        private const val DefaultThumbnailCount = 9
        private const val DefaultThumbnailWidth = 320
        private const val DefaultThumbnailHeight = 180
    }
}
