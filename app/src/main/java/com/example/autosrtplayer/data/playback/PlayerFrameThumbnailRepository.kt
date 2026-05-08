package com.example.autosrtplayer.data.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class PlayerFrameThumbnailRepository(
    private val playerFactory: PlayerFactory = PlayerFactory()
) {
    suspend fun loadThumbnails(
        context: Context,
        request: VideoThumbnailKey,
        count: Int = 9,
        width: Int = 320,
        height: Int = 180
    ): List<VideoFrameThumbnail> = withContext(Dispatchers.Main) {
        runCatching {
            val player = playerFactory.create(context, request.userAgent, request.referrer)
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            try {
                player.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                player.setVideoSurface(imageReader.surface)
                player.setMediaItem(MediaItem.fromUri(request.mediaUrl))
                player.playWhenReady = true
                player.prepare()
                waitUntilReady(player)

                val times = buildTimes(request.durationMs, count)
                times.map { timeMs ->
                    player.seekTo(timeMs)
                    waitUntilReady(player)
                    delay(350)
                    VideoFrameThumbnail(timeMs = timeMs, bitmap = captureBitmapSafely(imageReader))
                }
            } finally {
                player.playWhenReady = false
                player.release()
                imageReader.close()
            }
        }.getOrElse {
            emptyList()
        }
    }

    private suspend fun waitUntilReady(player: ExoPlayer, timeoutMs: Long = 4_000L) {
        var waited = 0L
        while (player.playbackState != Player.STATE_READY && waited < timeoutMs) {
            delay(50)
            waited += 50
        }
    }

    private fun buildTimes(durationMs: Long, count: Int): List<Long> {
        val safeCount = count.coerceAtLeast(1)
        val safeDuration = durationMs.coerceAtLeast(1L)
        return List(safeCount) { index -> safeDuration * (index + 1) / (safeCount + 1) }
    }

    private fun captureBitmapSafely(imageReader: ImageReader): Bitmap? = runCatching {
        val image = imageReader.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * it.width
            if (it.width <= 0 || it.height <= 0 || pixelStride <= 0) return null
            val bitmap = Bitmap.createBitmap(
                it.width + rowPadding / pixelStride,
                it.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(bitmap, 0, 0, it.width, it.height)
        }
    }.getOrNull()
}
