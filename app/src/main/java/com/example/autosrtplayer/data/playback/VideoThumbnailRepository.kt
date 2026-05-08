package com.example.autosrtplayer.data.playback

import android.graphics.Bitmap
import android.app.ActivityManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoThumbnailRepository {
    data class ThumbnailDecodePolicy(
        val count: Int,
        val targetWidth: Int,
        val targetHeight: Int
    )

    suspend fun loadThumbnails(
        request: VideoThumbnailKey,
        policy: ThumbnailDecodePolicy = ThumbnailDecodePolicy(
            count = DefaultThumbnailCount,
            targetWidth = DefaultThumbnailWidth,
            targetHeight = DefaultThumbnailHeight
        )
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

            // Single-thread decode by design to protect low-end devices (no 9-way parallel decode).
            buildThumbnailTimes(request.durationMs, policy.count).map { timeMs ->
                VideoFrameThumbnail(
                    timeMs = timeMs,
                    bitmap = extractFrame(retriever, timeMs, policy.targetWidth, policy.targetHeight)
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

        fun buildPolicy(
            context: Context,
            durationMs: Long,
            preferMaxHeight: Int = 480
        ): ThumbnailDecodePolicy {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowRam = am?.isLowRamDevice ?: false
            val memoryClass = am?.memoryClass ?: 128
            val longVideo = durationMs >= 90L * 60L * 1000L

            val maxHeight = if (lowRam || memoryClass <= 192) 320 else preferMaxHeight.coerceAtMost(480)
            val baseCount = when {
                lowRam -> 4
                longVideo -> 6
                else -> DefaultThumbnailCount
            }
            return ThumbnailDecodePolicy(
                count = baseCount,
                targetWidth = maxHeight * 16 / 9,
                targetHeight = maxHeight
            )
        }
    }
}
