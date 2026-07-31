package com.example.autosrtplayer.ui.vr

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import com.example.autosrtplayer.ui.VrPlaybackConfig
import com.example.autosrtplayer.ui.VrViewAngles

@Composable
fun VrPlayerSurface(
    player: ExoPlayer?,
    config: VrPlaybackConfig,
    viewAngles: VrViewAngles,
    modifier: Modifier = Modifier
) {
    // Guard: Only proceed if player is valid and has media
    if (player == null || player.currentMediaItem == null) {
        android.util.Log.w("VrPlayerSurface", "Skipping VR surface setup: player=${player != null}, hasMedia=${player?.currentMediaItem != null}")
        return
    }

    val context = LocalContext.current
    val glView = remember { createGLSurfaceView(context) }
    val renderer = remember { glView.tag as VrRenderer }
    var videoSurface by remember { mutableStateOf<Surface?>(null) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Set up surface-ready callback
    DisposableEffect(Unit) {
        renderer.setOnSurfaceReadyListener { surface ->
            // Post to main thread to update Compose state safely
            mainHandler.post {
                if (surface.isValid) {
                    videoSurface = surface
                    android.util.Log.d("VrPlayerSurface", "Video surface ready: $surface")
                } else {
                    android.util.Log.w("VrPlayerSurface", "Received invalid surface, skipping")
                }
            }
        }

        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            videoSurface = null
            glView.queueEvent {
                renderer.release()
            }
        }
    }

    // Manage video size listener per player instance
    DisposableEffect(player) {
        val currentPlayer = player
        if (currentPlayer != null) {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val width = videoSize.width
                    val height = videoSize.height
                    if (width <= 0 || height <= 0) {
                        android.util.Log.w("VrPlayerSurface", "Invalid video size: ${width}x${height}")
                        return
                    }
                    val pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                    val effectiveWidth = width * pixelRatio
                    val aspectRatio = effectiveWidth / height
                    android.util.Log.d("VrPlayerSurface", "Video size changed: ${width}x${height}, aspect=$aspectRatio")
                    glView.queueEvent {
                        renderer.setVideoAspectRatio(aspectRatio)
                        renderer.requestFrameUpdate()
                    }
                }
            }
            currentPlayer.addListener(listener)

            // Sync initial video size if already available
            val initialVideoSize = currentPlayer.videoSize
            if (initialVideoSize.width > 0 && initialVideoSize.height > 0) {
                val width = initialVideoSize.width
                val height = initialVideoSize.height
                val pixelRatio = if (initialVideoSize.pixelWidthHeightRatio > 0f) initialVideoSize.pixelWidthHeightRatio else 1f
                val effectiveWidth = width * pixelRatio
                val aspectRatio = effectiveWidth / height
                glView.queueEvent {
                    renderer.setVideoAspectRatio(aspectRatio)
                    renderer.requestFrameUpdate()
                }
            }

            onDispose {
                currentPlayer.removeListener(listener)
            }
        } else {
            onDispose { }
        }
    }

    // Bind surface to player
    DisposableEffect(player, videoSurface) {
        val surface = videoSurface
        val currentPlayer = player

        if (currentPlayer != null && surface != null && surface.isValid) {
            try {
                android.util.Log.d("VrPlayerSurface", "Binding surface to player")
                currentPlayer.setVideoSurface(surface)
            } catch (e: Exception) {
                android.util.Log.e("VrPlayerSurface", "Failed to bind surface to player", e)
            }
        } else {
            android.util.Log.w("VrPlayerSurface", "Skipping surface bind: player=${currentPlayer != null}, surface=${surface != null}, valid=${surface?.isValid}")
        }

        onDispose {
            if (currentPlayer != null && surface != null && surface.isValid) {
                try {
                    currentPlayer.clearVideoSurface(surface)
                    android.util.Log.d("VrPlayerSurface", "Cleared surface from player")
                } catch (e: Exception) {
                    android.util.Log.e("VrPlayerSurface", "Failed to clear surface from player", e)
                }
            }
        }
    }

    // Update config and view angles
    DisposableEffect(config, viewAngles) {
        glView.queueEvent {
            renderer.setConfig(config)
            renderer.setViewAngles(viewAngles)
            renderer.requestFrameUpdate()
        }

        onDispose { }
    }

    AndroidView(
        factory = { glView },
        modifier = modifier
    )
}

private fun createGLSurfaceView(context: Context): GLSurfaceView {
    return GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        val vrRenderer = VrRenderer()
        vrRenderer.setOnRequestRenderListener {
            requestRender()
        }
        setRenderer(vrRenderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        tag = vrRenderer
    }
}
