package com.example.autosrtplayer.data.playback

import android.graphics.Bitmap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class ExoPlayerFrameGrabber(
    private val captureFrame: suspend (timeMs: Long, timeoutMs: Long) -> Bitmap?
) {
    suspend fun captureThumbnails(
        request: VideoThumbnailKey,
        count: Int = DefaultThumbnailCount,
        perFrameTimeoutMs: Long = DefaultPerFrameTimeoutMs,
        retryCount: Int = DefaultRetryCount
    ): List<VideoFrameThumbnail> {
        val times = buildThumbnailTimes(request.durationMs, count)
        return times.map { timeMs ->
            var bitmap: Bitmap? = null
            repeat(retryCount.coerceAtLeast(1)) {
                if (bitmap != null) return@repeat
                bitmap = try {
                    withTimeout(perFrameTimeoutMs) {
                        captureFrame(timeMs, perFrameTimeoutMs)
                    }
                } catch (_: TimeoutCancellationException) {
                    null
                } catch (_: Throwable) {
                    null
                }
            }
            VideoFrameThumbnail(timeMs = timeMs, bitmap = bitmap)
        }
    }

    private fun buildThumbnailTimes(durationMs: Long, count: Int): List<Long> {
        val safeCount = count.coerceAtLeast(1)
        val safeDuration = durationMs.coerceAtLeast(1L)
        return List(safeCount) { index ->
            safeDuration * (index + 1) / (safeCount + 1)
        }
    }

    companion object {
        private const val DefaultThumbnailCount = 9
        private const val DefaultPerFrameTimeoutMs = 1_500L
        private const val DefaultRetryCount = 2
    }
}
