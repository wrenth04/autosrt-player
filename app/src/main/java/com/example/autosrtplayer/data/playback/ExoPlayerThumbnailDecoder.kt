package com.example.autosrtplayer.data.playback

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
class ExoPlayerThumbnailDecoder(
    private val context: Context,
    private val playerFactory: PlayerFactory = PlayerFactory()
) {
    suspend fun loadThumbnails(
        textureView: TextureView,
        request: VideoThumbnailKey,
        count: Int = DefaultThumbnailCount,
        targetWidth: Int = DefaultThumbnailWidth,
        targetHeight: Int = DefaultThumbnailHeight
    ): List<VideoFrameThumbnail> = withContext(Dispatchers.Main.immediate) {
        val player = playerFactory.create(
            context = context,
            userAgent = request.userAgent,
            referrer = request.referrer
        )
        try {
            player.volume = 0f
            player.playWhenReady = true
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setVideoTextureView(textureView)
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(request.mediaUrl)
                    .setMediaId(request.mediaUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
            )
            player.prepare()
            if (!awaitReady(player, ReadyTimeoutMs)) {
                throw IllegalStateException("播放器初始化逾時")
            }

            val thumbnails = mutableListOf<VideoFrameThumbnail>()
            for (timeMs in buildThumbnailTimes(request.durationMs, count)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                player.seekTo(timeMs)
                player.playWhenReady = true
                delay(FrameSettleDelayMs)
                val bitmap = captureBitmap(textureView, targetWidth, targetHeight)
                thumbnails += VideoFrameThumbnail(timeMs = timeMs, bitmap = bitmap)
            }
            thumbnails
        } finally {
            runCatching { player.clearVideoSurface() }
            runCatching { player.release() }
        }
    }

    private suspend fun awaitReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        val ready = withTimeoutOrNull(timeoutMs) {
            while (player.playbackState != Player.STATE_READY) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                delay(50L)
            }
            true
        }
        return ready == true
    }

    private fun captureBitmap(textureView: TextureView, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (!textureView.isAvailable) return null
        val bitmap = runCatching {
            textureView.getBitmap(targetWidth, targetHeight)
        }.getOrNull() ?: return null
        return bitmap
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
        private const val DefaultThumbnailWidth = 320
        private const val DefaultThumbnailHeight = 180
        private const val ReadyTimeoutMs = 8000L
        private const val FrameSettleDelayMs = 250L
    }
}
