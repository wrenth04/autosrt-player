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
    val context = LocalContext.current
    val glView = remember { createGLSurfaceView(context) }
    val renderer = remember { glView.tag as VrRenderer }
    var videoSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        renderer.setOnSurfaceReadyListener { surface ->
            videoSurface = surface
        }
        onDispose {
            glView.queueEvent {
                renderer.release()
            }
        }
    }

    DisposableEffect(player, videoSurface) {
        val surface = videoSurface
        if (player != null && surface != null) {
            player.setVideoSurface(surface)
        }

        onDispose {
            if (surface != null) {
                player?.clearVideoSurface(surface)
            }
        }
    }

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
