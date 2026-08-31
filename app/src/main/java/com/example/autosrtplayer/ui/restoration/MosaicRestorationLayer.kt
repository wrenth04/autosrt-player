package com.example.autosrtplayer.ui.restoration

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ai.onnxruntime.OrtException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.autosrtplayer.data.restoration.DetectedMosaicRegion
import com.example.autosrtplayer.data.restoration.OnnxMosaicDetector
import com.example.autosrtplayer.data.restoration.OnnxMosaicRestorer
import com.example.autosrtplayer.data.restoration.RestorationModel
import com.example.autosrtplayer.data.restoration.RestoredImage
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
internal fun MosaicRestorationLayer(
    playerView: PlayerView,
    player: ExoPlayer,
    config: MosaicRestorationConfig,
    autoDetectionConfig: MosaicAutoDetectionConfig,
    model: RestorationModel?,
    modelFile: File?,
    detectorModelFile: File?,
    isRegionEditing: Boolean,
    onRegionChange: (NormalizedRegion) -> Unit,
    onEditingFinished: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnError by rememberUpdatedState(onError)
    var surfaceBounds by remember { mutableStateOf<SurfaceBounds?>(null) }
    var restoredPreview by remember { mutableStateOf<RestorationPreview?>(null) }
    var autoDetectedRegion by remember { mutableStateOf<NormalizedRegion?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var detectorError by remember { mutableStateOf<String?>(null) }

    val restorerState by produceState<RestorerState>(
        initialValue = RestorerState.Unavailable,
        key1 = config.enabled,
        key2 = model,
        key3 = modelFile
    ) {
        if (!config.enabled || model == null || modelFile == null) {
            value = RestorerState.Unavailable
            return@produceState
        }

        val restorer = try {
            withContext(NonCancellable + Dispatchers.IO) {
                OnnxMosaicRestorer(model, modelFile)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: OrtException) {
            val message = error.message ?: "ONNX Runtime 無法載入模型"
            latestOnError(message)
            value = RestorerState.Error(message)
            return@produceState
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "AI 模型格式無效"
            latestOnError(message)
            value = RestorerState.Error(message)
            return@produceState
        } catch (error: IllegalStateException) {
            val message = error.message ?: "AI 模型資料不完整"
            latestOnError(message)
            value = RestorerState.Error(message)
            return@produceState
        }

        if (!isActive) {
            restorer.close()
            return@produceState
        }
        value = RestorerState.Ready(restorer)
        awaitDispose {
            restorer.close()
        }
    }

    val detectorState by produceState<DetectorState>(
        initialValue = DetectorState.Unavailable,
        key1 = config.enabled,
        key2 = autoDetectionConfig.enabled,
        key3 = detectorModelFile
    ) {
        if (!config.enabled || !autoDetectionConfig.enabled || detectorModelFile == null) {
            value = DetectorState.Unavailable
            return@produceState
        }

        val detector = try {
            withContext(NonCancellable + Dispatchers.IO) {
                OnnxMosaicDetector(detectorModelFile)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: OrtException) {
            val message = error.message ?: "ONNX Runtime 無法載入偵測模型"
            latestOnError(message)
            value = DetectorState.Error(message)
            return@produceState
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "馬賽克偵測模型格式無效"
            latestOnError(message)
            value = DetectorState.Error(message)
            return@produceState
        } catch (error: IllegalStateException) {
            val message = error.message ?: "馬賽克偵測模型資料不完整"
            latestOnError(message)
            value = DetectorState.Error(message)
            return@produceState
        }

        if (!isActive) {
            detector.close()
            return@produceState
        }
        value = DetectorState.Ready(detector)
        awaitDispose {
            detector.close()
        }
    }

    LaunchedEffect(playerView, config.enabled, isRegionEditing) {
        if (!config.enabled && !isRegionEditing) {
            surfaceBounds = null
            return@LaunchedEffect
        }
        while (isActive) {
            surfaceBounds = resolveSurfaceBounds(playerView, playerView.videoSurfaceView)
            delay(if (isRegionEditing) GeometryRefreshMs else IdleGeometryRefreshMs)
        }
    }

    LaunchedEffect(
        playerView,
        player,
        config.enabled,
        autoDetectionConfig.enabled,
        autoDetectionConfig.threshold,
        detectorState
    ) {
        autoDetectedRegion = null
        detectorError = null
        val readyState = detectorState as? DetectorState.Ready ?: return@LaunchedEffect
        if (!config.enabled || !autoDetectionConfig.enabled) return@LaunchedEffect

        val regionTracker = MosaicRegionTracker()
        var lastProcessedPositionMs: Long? = null
        var trackedMediaItem = player.currentMediaItem
        while (isActive) {
            val currentMediaItem = player.currentMediaItem
            if (currentMediaItem !== trackedMediaItem) {
                regionTracker.reset()
                autoDetectedRegion = null
                lastProcessedPositionMs = null
                trackedMediaItem = currentMediaItem
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                regionTracker.reset()
                autoDetectedRegion = null
                lastProcessedPositionMs = null
                delay(PausedPreviewDelayMs)
                continue
            }
            if (currentMediaItem == null ||
                player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_BUFFERING
            ) {
                if (currentMediaItem == null) {
                    regionTracker.reset()
                    autoDetectedRegion = null
                    lastProcessedPositionMs = null
                }
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (!player.isPlaying && lastProcessedPositionMs == player.currentPosition) {
                delay(PausedPreviewDelayMs)
                continue
            }

            val videoSurface = playerView.videoSurfaceView
            if (videoSurface == null) {
                autoDetectedRegion = null
                detectorError = "播放器沒有可供自動偵測的影像 Surface"
                latestOnError(requireNotNull(detectorError))
                return@LaunchedEffect
            }

            when (
                val capture = captureVideoFrame(
                    videoSurface = videoSurface,
                    destinationWidth = readyState.detector.modelInfo.inputWidth,
                    destinationHeight = readyState.detector.modelInfo.inputHeight,
                    handler = mainHandler
                )
            ) {
                CaptureResult.NoFrame -> delay(PlayerNotReadyDelayMs)
                is CaptureResult.Error -> {
                    autoDetectedRegion = null
                    detectorError = capture.message
                    latestOnError(capture.message)
                    return@LaunchedEffect
                }
                is CaptureResult.Success -> {
                    val detection = try {
                        readyState.detector.detect(
                            bitmap = capture.bitmap,
                            threshold = autoDetectionConfig.threshold
                        )
                    } catch (error: CancellationException) {
                        capture.bitmap.recycle()
                        throw error
                    } catch (error: OrtException) {
                        capture.bitmap.recycle()
                        val message = error.message ?: "馬賽克範圍偵測失敗"
                        autoDetectedRegion = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalStateException) {
                        capture.bitmap.recycle()
                        val message = error.message ?: "馬賽克偵測輸出格式不符"
                        autoDetectedRegion = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalArgumentException) {
                        capture.bitmap.recycle()
                        val message = error.message ?: "馬賽克偵測輸入格式不符"
                        autoDetectedRegion = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    }
                    capture.bitmap.recycle()
                    val detected = detection.region?.toNormalizedRegion()
                    autoDetectedRegion = regionTracker.update(detected)
                    lastProcessedPositionMs = player.currentPosition
                    detectorError = null
                    delay(
                        max(
                            MinimumDetectionIntervalMs,
                            (detection.inferenceDurationMs * DetectionCooldownMultiplier).toLong()
                        )
                    )
                }
            }
        }
    }

    val latestAutoDetectedRegion by rememberUpdatedState(autoDetectedRegion)

    LaunchedEffect(
        playerView,
        player,
        config.enabled,
        config.region,
        autoDetectionConfig.enabled,
        model,
        restorerState
    ) {
        restoredPreview = null
        captureError = null
        val readyState = restorerState as? RestorerState.Ready ?: return@LaunchedEffect
        if (!config.enabled || model == null) return@LaunchedEffect

        var consecutiveCaptureFailures = 0
        var lastProcessedPositionMs: Long? = null
        var trackedMediaItem = player.currentMediaItem
        while (isActive) {
            val currentMediaItem = player.currentMediaItem
            if (currentMediaItem !== trackedMediaItem) {
                restoredPreview = null
                lastProcessedPositionMs = null
                trackedMediaItem = currentMediaItem
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                restoredPreview = null
                delay(PausedPreviewDelayMs)
                continue
            }
            if (currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
                restoredPreview = null
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (player.playbackState == Player.STATE_BUFFERING) {
                restoredPreview = null
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (!player.isPlaying && lastProcessedPositionMs == player.currentPosition) {
                delay(PausedPreviewDelayMs)
                continue
            }

            val safeRegion = if (autoDetectionConfig.enabled) {
                latestAutoDetectedRegion
            } else {
                config.region.sanitized()
            }
            if (safeRegion == null) {
                restoredPreview = null
                delay(PlayerNotReadyDelayMs)
                continue
            }

            val videoSurface = playerView.videoSurfaceView
            if (videoSurface == null) {
                val message = "播放器沒有可擷取的影像 Surface"
                restoredPreview = null
                captureError = message
                latestOnError(message)
                return@LaunchedEffect
            }

            val bounds = resolveSurfaceBounds(playerView, videoSurface)
            if (bounds == null) {
                delay(PlayerNotReadyDelayMs)
                continue
            }
            surfaceBounds = bounds

            val videoSize = player.videoSize
            if (videoSize.width <= 0 || videoSize.height <= 0) {
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (videoSize.unappliedRotationDegrees % 360 != 0) {
                val message = "目前不支援尚未套用旋轉資訊的影片"
                restoredPreview = null
                captureError = message
                latestOnError(message)
                return@LaunchedEffect
            }

            val sourceRect = calculateRestorationSourceRegion(
                region = safeRegion,
                videoWidth = videoSize.width,
                videoHeight = videoSize.height
            ).toRect()
            val textureSourceRect = safeRegion.toPixelRect(
                width = videoSurface.width,
                height = videoSurface.height
            )
            val inferenceSize = calculateRestorationInferenceSize(
                sourceWidth = sourceRect.width(),
                sourceHeight = sourceRect.height(),
                maxEdge = model.maxInputEdge
            )

            when (
                val capture = captureVideoRegion(
                    videoSurface = videoSurface,
                    sourceRect = sourceRect,
                    textureSourceRect = textureSourceRect,
                    destinationWidth = inferenceSize.width,
                    destinationHeight = inferenceSize.height,
                    handler = mainHandler
                )
            ) {
                CaptureResult.NoFrame -> {
                    consecutiveCaptureFailures += 1
                    if (consecutiveCaptureFailures >= MaxTransientCaptureFailures) {
                        Log.w(Tag, "Video surface has not produced a capturable frame yet")
                        consecutiveCaptureFailures = 0
                    }
                    delay(PlayerNotReadyDelayMs)
                }

                is CaptureResult.Error -> {
                    restoredPreview = null
                    captureError = capture.message
                    latestOnError(capture.message)
                    return@LaunchedEffect
                }

                is CaptureResult.Success -> {
                    consecutiveCaptureFailures = 0
                    val restored = try {
                        readyState.restorer.restore(capture.bitmap)
                    } catch (error: CancellationException) {
                        capture.bitmap.recycle()
                        throw error
                    } catch (error: OrtException) {
                        capture.bitmap.recycle()
                        val message = error.message ?: "AI 推論失敗"
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalStateException) {
                        capture.bitmap.recycle()
                        val message = error.message ?: "AI 模型輸出格式不符"
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalArgumentException) {
                        capture.bitmap.recycle()
                        val message = error.message ?: "AI 模型輸入格式不符"
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    }
                    capture.bitmap.recycle()
                    restoredPreview = RestorationPreview(
                        image = restored,
                        region = safeRegion
                    )
                    captureError = null
                    lastProcessedPositionMs = player.currentPosition

                    val nextFrameDelay = max(
                        MinimumPreviewIntervalMs,
                        (restored.inferenceDurationMs * InferenceCooldownMultiplier).toLong()
                    )
                    delay(nextFrameDelay)
                }
            }
        }
    }

    Box(modifier = modifier) {
        val bounds = surfaceBounds
        val preview = restoredPreview
        DisposableEffect(preview?.image?.bitmap) {
            val bitmap = preview?.image?.bitmap
            onDispose {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        if (config.enabled && bounds != null && preview != null) {
            RestoredRegion(
                image = preview.image,
                bounds = bounds,
                region = preview.region,
                strength = config.strength.coerceIn(
                    MosaicRestorationConfig.MinStrength,
                    MosaicRestorationConfig.MaxStrength
                )
            )

            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.62f)
                )
            ) {
                Text(
                    text = if (autoDetectionConfig.enabled) {
                        "AI 自動偵測修復（推測畫面）"
                    } else {
                        "AI 局部修復預覽（推測畫面）"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        val visibleError = detectorError
            ?: captureError
            ?: (detectorState as? DetectorState.Error)?.message
            ?: (restorerState as? RestorerState.Error)?.message
        if (config.enabled && visibleError != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "AI 修復預覽停止：$visibleError",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (isRegionEditing) {
            RegionEditor(
                surfaceBounds = bounds,
                initialRegion = config.region.sanitized(),
                onRegionChange = onRegionChange,
                onEditingFinished = onEditingFinished,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RestoredRegion(
    image: RestoredImage,
    bounds: SurfaceBounds,
    region: NormalizedRegion,
    strength: Float
) {
    val density = LocalDensity.current
    val target = bounds.regionRect(region)
    val width = with(density) { target.width().toDp() }
    val height = with(density) { target.height().toDp() }

    Image(
        bitmap = image.bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        alpha = strength,
        modifier = Modifier
            .offset { IntOffset(target.left, target.top) }
            .size(width = width, height = height)
    )
}

@Composable
private fun RegionEditor(
    surfaceBounds: SurfaceBounds?,
    initialRegion: NormalizedRegion,
    onRegionChange: (NormalizedRegion) -> Unit,
    onEditingFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onEditingFinished)
    var draftRegion by remember(initialRegion) { mutableStateOf(initialRegion) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    val latestOnRegionChange by rememberUpdatedState(onRegionChange)

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.18f))
            .pointerInput(surfaceBounds) {
                val bounds = surfaceBounds ?: return@pointerInput
                detectDragGestures(
                    onDragStart = { position ->
                        dragStart = bounds.toNormalizedOffset(position)
                    },
                    onDragEnd = {
                        dragStart = null
                        latestOnRegionChange(draftRegion.sanitized())
                    },
                    onDragCancel = {
                        dragStart = null
                        draftRegion = initialRegion
                    }
                ) { change, _ ->
                    change.consume()
                    val start = dragStart ?: return@detectDragGestures
                    val current = bounds.toNormalizedOffset(change.position)
                    draftRegion = NormalizedRegion.fromPoints(
                        firstX = start.x,
                        firstY = start.y,
                        secondX = current.x,
                        secondY = current.y
                    )
                }
            }
    ) {
        val bounds = surfaceBounds
        if (bounds != null) {
            val selectedRect = bounds.regionRect(draftRegion)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color(0xff64ddff),
                    topLeft = Offset(selectedRect.left.toFloat(), selectedRect.top.toFloat()),
                    size = Size(selectedRect.width().toFloat(), selectedRect.height().toFloat()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
                val handleRadius = 6.dp.toPx()
                listOf(
                    Offset(selectedRect.left.toFloat(), selectedRect.top.toFloat()),
                    Offset(selectedRect.right.toFloat(), selectedRect.top.toFloat()),
                    Offset(selectedRect.left.toFloat(), selectedRect.bottom.toFloat()),
                    Offset(selectedRect.right.toFloat(), selectedRect.bottom.toFloat())
                ).forEach { center ->
                    drawCircle(color = Color(0xff64ddff), radius = handleRadius, center = center)
                }
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.78f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("拖曳框選馬賽克區域", color = Color.White)
                Text(
                    "只處理框內畫面；AI 無法還原真實細節",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = onEditingFinished,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text("完成框選")
        }
    }
}

private suspend fun captureVideoRegion(
    videoSurface: View,
    sourceRect: Rect,
    textureSourceRect: Rect,
    destinationWidth: Int,
    destinationHeight: Int,
    handler: Handler
): CaptureResult {
    val bitmap = Bitmap.createBitmap(
        destinationWidth,
        destinationHeight,
        Bitmap.Config.ARGB_8888
    )

    return when (videoSurface) {
        is SurfaceView -> captureSurfaceView(videoSurface, sourceRect, bitmap, handler)
        is TextureView -> {
            bitmap.recycle()
            val frame = videoSurface.bitmap ?: return CaptureResult.NoFrame
            var cropped: Bitmap? = null
            try {
                val scaleX = frame.width.toFloat() / videoSurface.width
                val scaleY = frame.height.toFloat() / videoSurface.height
                val left = (textureSourceRect.left * scaleX)
                    .roundToInt()
                    .coerceIn(0, frame.width - 1)
                val top = (textureSourceRect.top * scaleY)
                    .roundToInt()
                    .coerceIn(0, frame.height - 1)
                val scaledSource = Rect(
                    left,
                    top,
                    (textureSourceRect.right * scaleX)
                        .roundToInt()
                        .coerceIn(left + 1, frame.width),
                    (textureSourceRect.bottom * scaleY)
                        .roundToInt()
                        .coerceIn(top + 1, frame.height)
                )
                cropped = Bitmap.createBitmap(
                    frame,
                    scaledSource.left,
                    scaledSource.top,
                    scaledSource.width(),
                    scaledSource.height()
                )
                val scaled = Bitmap.createScaledBitmap(
                    cropped,
                    destinationWidth,
                    destinationHeight,
                    true
                )
                if (scaled !== cropped && cropped !== frame) {
                    cropped.recycle()
                }
                if (scaled !== frame) {
                    frame.recycle()
                }
                CaptureResult.Success(scaled)
            } catch (error: IllegalArgumentException) {
                val failedCrop = cropped
                if (failedCrop != null && failedCrop !== frame && !failedCrop.isRecycled) {
                    failedCrop.recycle()
                }
                frame.recycle()
                Log.e(Tag, "Invalid TextureView capture region", error)
                CaptureResult.Error("框選範圍無法擷取")
            }
        }
        else -> {
            bitmap.recycle()
            CaptureResult.Error("目前的影片 Surface 不支援畫面擷取")
        }
    }
}

private suspend fun captureVideoFrame(
    videoSurface: View,
    destinationWidth: Int,
    destinationHeight: Int,
    handler: Handler
): CaptureResult {
    return when (videoSurface) {
        is SurfaceView -> {
            val bitmap = Bitmap.createBitmap(
                destinationWidth,
                destinationHeight,
                Bitmap.Config.ARGB_8888
            )
            captureSurfaceView(videoSurface, null, bitmap, handler)
        }
        is TextureView -> {
            if (!videoSurface.isAvailable || videoSurface.width <= 0 || videoSurface.height <= 0) {
                CaptureResult.NoFrame
            } else {
                val frame = try {
                    videoSurface.getBitmap(destinationWidth, destinationHeight)
                } catch (error: IllegalStateException) {
                    Log.d(Tag, "TextureView frame is temporarily unavailable", error)
                    null
                } catch (error: IllegalArgumentException) {
                    Log.e(Tag, "Invalid TextureView capture size", error)
                    return CaptureResult.Error("自動偵測畫面尺寸無效")
                }
                if (frame == null) {
                    CaptureResult.NoFrame
                } else {
                    CaptureResult.Success(frame)
                }
            }
        }
        else -> {
            CaptureResult.Error("目前的影片 Surface 不支援畫面擷取")
        }
    }
}

private suspend fun captureSurfaceView(
    surfaceView: SurfaceView,
    sourceRect: Rect?,
    bitmap: Bitmap,
    handler: Handler
): CaptureResult = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
    if (!surfaceView.holder.surface.isValid) {
        bitmap.recycle()
        continuation.resume(CaptureResult.NoFrame)
        return@suspendCancellableCoroutine
    }
    try {
        val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
            if (!continuation.isActive) {
                bitmap.recycle()
            } else {
                val capture = when (result) {
                    PixelCopy.SUCCESS -> CaptureResult.Success(bitmap)
                    PixelCopy.ERROR_SOURCE_NO_DATA,
                    PixelCopy.ERROR_SOURCE_INVALID,
                    PixelCopy.ERROR_TIMEOUT -> {
                        bitmap.recycle()
                        CaptureResult.NoFrame
                    }
                    else -> {
                        bitmap.recycle()
                        CaptureResult.Error("PixelCopy 失敗（代碼 $result）")
                    }
                }
                continuation.resume(capture)
            }
        }
        if (sourceRect == null) {
            PixelCopy.request(surfaceView, bitmap, listener, handler)
        } else {
            PixelCopy.request(surfaceView, sourceRect, bitmap, listener, handler)
        }
    } catch (error: IllegalArgumentException) {
        bitmap.recycle()
        Log.e(Tag, "Invalid PixelCopy request", error)
        continuation.resume(CaptureResult.Error("框選範圍無法擷取"))
    }
}

private fun resolveSurfaceBounds(playerView: PlayerView, videoSurface: View?): SurfaceBounds? {
    val surface = videoSurface ?: return null
    if (surface.width <= 0 || surface.height <= 0 || playerView.width <= 0 || playerView.height <= 0) {
        return null
    }

    val playerLocation = IntArray(2)
    val surfaceLocation = IntArray(2)
    playerView.getLocationInWindow(playerLocation)
    surface.getLocationInWindow(surfaceLocation)
    return SurfaceBounds(
        left = surfaceLocation[0] - playerLocation[0],
        top = surfaceLocation[1] - playerLocation[1],
        width = surface.width,
        height = surface.height
    )
}

private fun NormalizedRegion.toPixelRect(width: Int, height: Int): Rect {
    return calculateRestorationSourceRegion(this, width, height).toRect()
}

private fun PixelRegion.toRect(): Rect {
    return Rect(left, top, right, bottom)
}

private fun DetectedMosaicRegion.toNormalizedRegion(): NormalizedRegion {
    return NormalizedRegion(
        left = left,
        top = top,
        right = right,
        bottom = bottom
    ).sanitized()
}

private data class RestorationPreview(
    val image: RestoredImage,
    val region: NormalizedRegion
)

private data class SurfaceBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    fun regionRect(region: NormalizedRegion): Rect {
        val local = region.toPixelRect(width, height)
        return Rect(
            left + local.left,
            top + local.top,
            left + local.right,
            top + local.bottom
        )
    }

    fun toNormalizedOffset(offset: Offset): Offset {
        return Offset(
            x = ((offset.x - left) / width).coerceIn(0f, 1f),
            y = ((offset.y - top) / height).coerceIn(0f, 1f)
        )
    }
}

private sealed interface CaptureResult {
    data class Success(val bitmap: Bitmap) : CaptureResult
    data object NoFrame : CaptureResult
    data class Error(val message: String) : CaptureResult
}

private sealed interface RestorerState {
    data object Unavailable : RestorerState
    data class Ready(val restorer: OnnxMosaicRestorer) : RestorerState
    data class Error(val message: String) : RestorerState
}

private sealed interface DetectorState {
    data object Unavailable : DetectorState
    data class Ready(val detector: OnnxMosaicDetector) : DetectorState
    data class Error(val message: String) : DetectorState
}

private const val Tag = "MosaicRestoration"
private const val GeometryRefreshMs = 100L
private const val IdleGeometryRefreshMs = 500L
private const val PlayerNotReadyDelayMs = 250L
private const val PausedPreviewDelayMs = 500L
private const val MinimumPreviewIntervalMs = 180L
private const val InferenceCooldownMultiplier = 1.15f
private const val MinimumDetectionIntervalMs = 750L
private const val DetectionCooldownMultiplier = 1.25f
private const val MaxTransientCaptureFailures = 12
