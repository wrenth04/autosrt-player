package com.example.autosrtplayer.data.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
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
            val headers = buildRequestHeaders(request.userAgent, request.referrer)
            Log.d(TAG, "loadThumbnails: mediaUrl=${request.mediaUrl}, headers=${headers.keys}")
            runCatching {
                if (headers.isEmpty()) {
                    retriever.setDataSource(request.mediaUrl)
                } else {
                    retriever.setDataSource(request.mediaUrl, headers)
                }
            }.getOrElse { cause ->
                val isHlsLike = request.mediaUrl.contains(".m3u8", ignoreCase = true)
                val message = if (isHlsLike) {
                    "目前來源為 HLS（m3u8），系統縮圖器無法直接擷取，請改用播放器截圖流程"
                } else {
                    "設定縮圖資料來源失敗：${cause.message ?: cause::class.java.simpleName}"
                }
                throw IllegalStateException(message, cause)
            }

            val thumbnails = buildThumbnailTimes(request.durationMs, count).map { timeMs ->
                VideoFrameThumbnail(
                    timeMs = timeMs,
                    bitmap = extractFrame(retriever, timeMs, targetWidth, targetHeight)
                )
            }

            val successCount = thumbnails.count { it.bitmap != null }
            Log.d(TAG, "loadThumbnails: success=$successCount/${thumbnails.size}")
            if (successCount == 0) {
                error("無法擷取任何縮圖，請確認來源是否支援直接擷取影格（常見於 HLS/防盜鏈來源限制）")
            }
            thumbnails
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
        val frame = extractFrameWithFallback(retriever, timeMs, targetWidth, targetHeight) ?: return null

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

    private fun extractFrameWithFallback(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val offsets = listOf(0L, 250L, -250L, 500L, -500L)
        val options = listOf(
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_CLOSEST
        )

        for (offsetMs in offsets) {
            val candidateMs = (timeMs + offsetMs).coerceAtLeast(0L)
            val candidateUs = candidateMs * 1_000L
            for (option in options) {
                val frame = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(candidateUs, option, targetWidth, targetHeight)
                    } else {
                        retriever.getFrameAtTime(candidateUs, option)
                    }
                }.getOrNull()
                if (frame != null) {
                    return frame
                }
            }
        }
        Log.w(TAG, "extractFrameWithFallback: failed at ${timeMs}ms")
        return null
    }

    companion object {
        private const val TAG = "VideoThumbnailRepo"
        private const val DefaultThumbnailCount = 9
        private const val DefaultThumbnailWidth = 320
        private const val DefaultThumbnailHeight = 180
    }
}
