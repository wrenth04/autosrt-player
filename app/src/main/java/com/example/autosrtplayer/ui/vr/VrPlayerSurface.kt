package com.example.autosrtplayer.ui.vr

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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

    DisposableEffect(player, config, viewAngles) {
        renderer.setConfig(config)
        renderer.setViewAngles(viewAngles)
        renderer.requestFrameUpdate()
        glView.requestRender()

        onDispose { }
    }

    DisposableEffect(player) {
        val surface = renderer.getVideoSurface()
        player?.setVideoSurface(surface)

        onDispose {
            player?.clearVideoSurface()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer.release()
        }
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
        vrRenderer.setOnSurfaceTextureReadyListener { surfaceTexture ->
            // Surface texture ready
        }
        setRenderer(vrRenderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        tag = vrRenderer
    }
}
