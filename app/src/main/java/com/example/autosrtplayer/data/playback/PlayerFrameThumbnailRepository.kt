package com.example.autosrtplayer.data.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
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
        count: Int = 3,
        width: Int = 320,
        height: Int = 180
    ): List<VideoFrameThumbnail> = withContext(Dispatchers.Main) {
        val player = playerFactory.create(context, request.userAgent, request.referrer)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        try {
            player.setVideoSurface(imageReader.surface)
            player.setMediaItem(MediaItem.fromUri(request.mediaUrl))
            player.prepare()

            val times = buildTimes(request.durationMs, count)
            times.map { timeMs ->
                player.seekTo(timeMs)
                player.playWhenReady = false
                delay(250)
                VideoFrameThumbnail(timeMs = timeMs, bitmap = captureBitmap(imageReader))
            }
        } finally {
            player.release()
            imageReader.close()
        }
    }

    private fun buildTimes(durationMs: Long, count: Int): List<Long> {
        val safeCount = count.coerceAtLeast(1)
        val safeDuration = durationMs.coerceAtLeast(1L)
        return List(safeCount) { index -> safeDuration * (index + 1) / (safeCount + 1) }
    }

    private fun captureBitmap(imageReader: ImageReader): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * it.width
            val bitmap = Bitmap.createBitmap(
                it.width + rowPadding / pixelStride,
                it.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(bitmap, 0, 0, it.width, it.height)
        }
    }
}
