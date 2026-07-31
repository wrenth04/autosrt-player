package com.example.autosrtplayer.ui.vr

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
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
import com.example.autosrtplayer.ui.vr.depth.DepthEstimator
import com.example.autosrtplayer.ui.vr.depth.DepthInput
import com.example.autosrtplayer.ui.vr.depth.TFLiteDepthEstimator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.example.autosrtplayer.ui.VrViewAngles

@Composable
fun VrPlayerSurface(
    player: ExoPlayer?,
    config: VrPlaybackConfig,
    viewAngles: VrViewAngles,
    selectedDepthModelId: String?,
    depthModelFilePath: String?,
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
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Depth estimator state
    var depthEstimator by remember { mutableStateOf<DepthEstimator?>(null) }
    val lastFrameCaptureTime = remember { AtomicLong(0L) }
    val isCapturingFrame = remember { AtomicBoolean(false) }

    // GLSurfaceView owns an EGL thread. Keep its resume/pause and renderer release in
    // one effect so the release work is queued before that thread is paused.
    DisposableEffect(glView, renderer) {
        glView.onResume()
        onDispose {
            renderer.setOnSurfaceReadyListener(null)
            mainHandler.removeCallbacksAndMessages(null)
            glView.queueEvent(renderer::release)
            glView.onPause()
        }
    }

    // A surface callback is delivered from the GL thread and may arrive after Compose
    // starts disposing this view. Ignore late callbacks instead of binding a stale surface.
    DisposableEffect(renderer) {
        val isActive = AtomicBoolean(true)
        renderer.setOnSurfaceReadyListener { surface ->
            mainHandler.post {
                if (isActive.get() && surface.isValid) {
                    videoSurface = surface
                    android.util.Log.d("VrPlayerSurface", "Video surface ready: $surface")
                } else {
                    android.util.Log.w("VrPlayerSurface", "Ignoring invalid or late video surface")
                }
            }
        }

        onDispose {
            isActive.set(false)
            renderer.setOnSurfaceReadyListener(null)
            videoSurface = null
        }
    }

    // Manage video size listener per player instance.
    DisposableEffect(player) {
        val currentPlayer = player
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val width = videoSize.width
                val height = videoSize.height
                if (width <= 0 || height <= 0) {
                    android.util.Log.w("VrPlayerSurface", "Invalid video size: ${width}x${height}")
                    return
                }
                val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                val aspectRatio = width * pixelRatio / height
                glView.queueEvent {
                    renderer.setVideoAspectRatio(aspectRatio)
                    renderer.requestFrameUpdate()
                }
            }
        }
        currentPlayer.addListener(listener)

        currentPlayer.videoSize.let { initialVideoSize ->
            if (initialVideoSize.width > 0 && initialVideoSize.height > 0) {
                val pixelRatio = initialVideoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                val aspectRatio = initialVideoSize.width * pixelRatio / initialVideoSize.height
                glView.queueEvent {
                    renderer.setVideoAspectRatio(aspectRatio)
                    renderer.requestFrameUpdate()
                }
            }
        }

        onDispose {
            currentPlayer.removeListener(listener)
        }
    }

    // Bind surface to player
    DisposableEffect(player, videoSurface) {
        val surface = videoSurface
        val currentPlayer = player

        if (surface != null && surface.isValid) {
            try {
                android.util.Log.d("VrPlayerSurface", "Binding surface to player")
                currentPlayer.setVideoSurface(surface)
            } catch (e: Exception) {
                android.util.Log.e("VrPlayerSurface", "Failed to bind surface to player", e)
            }
        } else {
            android.util.Log.w("VrPlayerSurface", "Skipping surface bind: surface=${surface != null}, valid=${surface?.isValid}")
        }

        onDispose {
            if (surface != null && surface.isValid) {
                try {
                    currentPlayer.clearVideoSurface(surface)
                    android.util.Log.d("VrPlayerSurface", "Cleared surface from player")
                } catch (e: Exception) {
                    android.util.Log.e("VrPlayerSurface", "Failed to clear surface from player", e)
                }
            }
        }
    }

    // Manage depth estimator lifecycle
    DisposableEffect(selectedDepthModelId, depthModelFilePath, config.depthStereoEnabled) {
        if (config.depthStereoEnabled &&
            config.isDepthStereoEligible() &&
            depthModelFilePath != null) {
            val modelFile = File(depthModelFilePath)
            if (modelFile.exists()) {
                try {
                    val estimator = TFLiteDepthEstimator(context, modelFile)
                    depthEstimator = estimator
                    android.util.Log.d("VrPlayerSurface", "Depth estimator initialized")
                } catch (e: Exception) {
                    android.util.Log.e("VrPlayerSurface", "Failed to initialize depth estimator", e)
                    depthEstimator = null
                }
            } else {
                android.util.Log.w("VrPlayerSurface", "Depth model file not found: $depthModelFilePath")
                depthEstimator = null
            }
        } else {
            depthEstimator?.release()
            depthEstimator = null
            // Clear depth frame when disabled
            glView.queueEvent {
                renderer.setDepthFrame(null)
            }
        }

        onDispose {
            depthEstimator?.release()
            depthEstimator = null
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

    // Depth frame capture loop
    DisposableEffect(depthEstimator, config.depthStereoEnabled) {
        val estimator = depthEstimator
        if (estimator != null && config.depthStereoEnabled && config.isDepthStereoEligible()) {
            val captureRunnable = object : Runnable {
                override fun run() {
                    if (depthEstimator != null && !isCapturingFrame.get()) {
                        val now = System.currentTimeMillis()
                        val lastCapture = lastFrameCaptureTime.get()

                        // Target ~5 FPS for depth inference to reduce jitter
                        if (now - lastCapture >= 200) {
                            captureAndProcessFrame(glView, renderer, estimator, lastFrameCaptureTime, isCapturingFrame)
                        }
                    }

                    if (depthEstimator != null) {
                        mainHandler.postDelayed(this, 100) // Check every 100ms
                    }
                }
            }

            mainHandler.post(captureRunnable)

            onDispose {
                mainHandler.removeCallbacks(captureRunnable)
            }
        } else {
            onDispose { }
        }
    }

    AndroidView(
        factory = { glView },
        modifier = modifier
    )
}

/**
 * Capture current GL frame and submit for depth estimation.
 */
private fun captureAndProcessFrame(
    glView: GLSurfaceView,
    renderer: VrRenderer,
    estimator: DepthEstimator,
    lastCaptureTime: AtomicLong,
    isCapturing: AtomicBoolean
) {
    if (!isCapturing.compareAndSet(false, true)) {
        return // Already capturing
    }

    glView.queueEvent {
        try {
            // Get current viewport dimensions
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val width = viewport[2]
            val height = viewport[3]

            if (width <= 0 || height <= 0) {
                android.util.Log.w("VrPlayerSurface", "Invalid viewport: ${width}x${height}")
                isCapturing.set(false)
                return@queueEvent
            }

            // Read pixels from framebuffer
            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
            pixelBuffer.order(ByteOrder.nativeOrder())

            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)

            val glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) {
                android.util.Log.e("VrPlayerSurface", "glReadPixels error: $glError")
                isCapturing.set(false)
                return@queueEvent
            }

            pixelBuffer.rewind()
            lastCaptureTime.set(System.currentTimeMillis())

            // Submit for depth estimation
            val depthInput = DepthInput(
                rgbaData = pixelBuffer,
                width = width,
                height = height,
                videoPts = System.currentTimeMillis() * 1000 // Convert to microseconds
            )

            estimator.submitFrame(depthInput) { depthFrame ->
                // Update renderer with new depth frame on GL thread
                glView.queueEvent {
                    renderer.setDepthFrame(depthFrame)
                    renderer.requestFrameUpdate()
                }
                isCapturing.set(false)
            }
        } catch (e: Exception) {
            android.util.Log.e("VrPlayerSurface", "Frame capture failed", e)
            isCapturing.set(false)
        }
    }
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
