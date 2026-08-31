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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import ai.onnxruntime.OrtException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.autosrtplayer.data.restoration.DetectedMosaicRegion
import com.example.autosrtplayer.data.restoration.MosaicProbabilityMask
import com.example.autosrtplayer.data.restoration.OnnxMosaicDetector
import com.example.autosrtplayer.data.restoration.OnnxMosaicRestorer
import com.example.autosrtplayer.data.restoration.RestorationModel
import com.example.autosrtplayer.data.restoration.RestoredImage
import com.example.autosrtplayer.data.restoration.createFeatheredMosaicMask
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@androidx.media3.common.util.UnstableApi
@Composable
internal fun MosaicRestorationLayer(
    playerView: PlayerView,
    player: ExoPlayer,
    config: MosaicRestorationConfig,
    autoDetectionConfig: MosaicAutoDetectionConfig,
    model: RestorationModel?,
    modelFile: File?,
    detectorModelFile: File?,
    processingRequestId: Long,
    onProcessingChange: (Boolean) -> Unit,
    isRegionEditing: Boolean,
    onRegionChange: (NormalizedRegion) -> Unit,
    onEditingFinished: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnError by rememberUpdatedState(onError)
    val latestOnProcessingChange by rememberUpdatedState(onProcessingChange)
    var surfaceBounds by remember { mutableStateOf<SurfaceBounds?>(null) }
    var displayedSourceFrame by remember { mutableStateOf<Bitmap?>(null) }
    var restoredPreview by remember { mutableStateOf<RestorationPreview?>(null) }
    var autoDetectionTarget by remember { mutableStateOf<AutoDetectionTarget?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var detectorError by remember { mutableStateOf<String?>(null) }
    var detectorProcessing by remember { mutableStateOf(false) }
    var frameCaptureProcessing by remember { mutableStateOf(false) }
    var capturedFrameCount by remember { mutableIntStateOf(0) }
    var totalFrameCount by remember { mutableIntStateOf(0) }
    var restorationProcessing by remember { mutableStateOf(false) }
    var pendingFeedback by remember {
        mutableStateOf<MosaicRestorationFeedback?>(null)
    }

    LaunchedEffect(processingRequestId) {
        if (processingRequestId > 0L) {
            pendingFeedback = MosaicRestorationFeedback.Preparing
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestOnProcessingChange(false)
        }
    }
    val playerIsPlaying by produceState(
        initialValue = player.isPlaying,
        key1 = player
    ) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                value = isPlaying
            }
        }
        player.addListener(listener)
        value = player.isPlaying
        awaitDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(config.processOnlyWhenPaused, playerIsPlaying) {
        if (config.processOnlyWhenPaused && playerIsPlaying) {
            displayedSourceFrame = null
            restoredPreview = null
            autoDetectionTarget = null
            pendingFeedback = null
        }
    }

    val restorerState by produceState<RestorerState>(
        RestorerState.Unavailable,
        config.enabled,
        model,
        modelFile,
        config.processOnlyWhenPaused,
        playerIsPlaying
    ) {
        if (!config.enabled ||
            model == null ||
            modelFile == null ||
            (config.processOnlyWhenPaused && playerIsPlaying)
        ) {
            value = RestorerState.Unavailable
            return@produceState
        }

        value = RestorerState.Loading
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
        DetectorState.Unavailable,
        config.enabled,
        autoDetectionConfig.enabled,
        detectorModelFile,
        config.processOnlyWhenPaused,
        playerIsPlaying
    ) {
        if (!config.enabled ||
            !autoDetectionConfig.enabled ||
            detectorModelFile == null ||
            (config.processOnlyWhenPaused && playerIsPlaying)
        ) {
            value = DetectorState.Unavailable
            return@produceState
        }

        value = DetectorState.Loading
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

    val isRestorerLoading = restorerState is RestorerState.Loading
    val isDetectorLoading = detectorState is DetectorState.Loading
    val activeFeedback = resolveMosaicRestorationFeedback(
        pendingFeedback = pendingFeedback,
        isRestorerLoading = isRestorerLoading,
        isDetectorLoading = isDetectorLoading,
        isDetecting = detectorProcessing,
        isCapturingFrames = frameCaptureProcessing,
        capturedFrameCount = capturedFrameCount,
        totalFrameCount = totalFrameCount,
        isRestoring = restorationProcessing
    )
    val isProcessing = detectorProcessing ||
        frameCaptureProcessing ||
        restorationProcessing ||
        isRestorerLoading ||
        isDetectorLoading ||
        activeFeedback?.isBusy == true

    LaunchedEffect(isProcessing) {
        latestOnProcessingChange(isProcessing)
    }

    LaunchedEffect(
        pendingFeedback,
        detectorError,
        captureError,
        detectorState,
        restorerState
    ) {
        if (detectorError != null ||
            captureError != null ||
            detectorState is DetectorState.Error ||
            restorerState is RestorerState.Error
        ) {
            pendingFeedback = null
        }
    }

    LaunchedEffect(pendingFeedback) {
        val feedback = pendingFeedback
        if (feedback?.isTemporaryResult == true) {
            delay(FeedbackResultVisibilityMs)
            if (pendingFeedback == feedback) {
                pendingFeedback = null
            }
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
        config.processOnlyWhenPaused,
        autoDetectionConfig.enabled,
        autoDetectionConfig.threshold,
        processingRequestId,
        detectorState
    ) {
        detectorProcessing = false
        autoDetectionTarget = null
        detectorError = null
        val readyState = detectorState as? DetectorState.Ready ?: return@LaunchedEffect
        if (!config.enabled || !autoDetectionConfig.enabled) return@LaunchedEffect

        val regionTracker = MosaicRegionTracker()
        var consecutiveCaptureFailures = 0
        var lastProcessedPositionMs: Long? = null
        var trackedMediaItem = player.currentMediaItem
        while (isActive) {
            val currentMediaItem = player.currentMediaItem
            if (currentMediaItem !== trackedMediaItem) {
                regionTracker.reset()
                autoDetectionTarget = null
                pendingFeedback = null
                lastProcessedPositionMs = null
                trackedMediaItem = currentMediaItem
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                regionTracker.reset()
                autoDetectionTarget = null
                lastProcessedPositionMs = null
                delay(PausedPreviewDelayMs)
                continue
            }
            if (config.processOnlyWhenPaused && player.isPlaying) {
                regionTracker.reset()
                autoDetectionTarget = null
                lastProcessedPositionMs = null
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (currentMediaItem == null ||
                player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_BUFFERING
            ) {
                if (currentMediaItem == null) {
                    regionTracker.reset()
                    autoDetectionTarget = null
                    lastProcessedPositionMs = null
                }
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (frameCaptureProcessing || restorationProcessing) {
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (!player.isPlaying &&
                lastProcessedPositionMs?.isNearPosition(player.currentPosition) == true
            ) {
                delay(PausedPreviewDelayMs)
                continue
            }

            val videoSurface = playerView.videoSurfaceView
            if (videoSurface == null) {
                autoDetectionTarget = null
                detectorError = "播放器沒有可供自動偵測的影像 Surface"
                latestOnError(requireNotNull(detectorError))
                return@LaunchedEffect
            }

            val detectionPositionMs = player.currentPosition
            detectorProcessing = true
            when (
                val capture = captureVideoFrame(
                    videoSurface = videoSurface,
                    destinationWidth = readyState.detector.modelInfo.inputWidth,
                    destinationHeight = readyState.detector.modelInfo.inputHeight,
                    handler = mainHandler
                )
            ) {
                CaptureResult.NoFrame -> {
                    detectorProcessing = false
                    consecutiveCaptureFailures += 1
                    if (consecutiveCaptureFailures >= MaxTransientCaptureFailures) {
                        val message = "無法從播放器擷取畫面供馬賽克偵測"
                        autoDetectionTarget = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    }
                    delay(PlayerNotReadyDelayMs)
                }
                is CaptureResult.Error -> {
                    detectorProcessing = false
                    autoDetectionTarget = null
                    detectorError = capture.message
                    latestOnError(capture.message)
                    return@LaunchedEffect
                }
                is CaptureResult.Success -> {
                    consecutiveCaptureFailures = 0
                    val detection = try {
                        readyState.detector.detect(
                            bitmap = capture.bitmap,
                            threshold = autoDetectionConfig.threshold
                        )
                    } catch (error: CancellationException) {
                        capture.bitmap.recycle()
                        detectorProcessing = false
                        throw error
                    } catch (error: OrtException) {
                        capture.bitmap.recycle()
                        detectorProcessing = false
                        val message = error.message ?: "馬賽克範圍偵測失敗"
                        autoDetectionTarget = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalStateException) {
                        capture.bitmap.recycle()
                        detectorProcessing = false
                        val message = error.message ?: "馬賽克偵測輸出格式不符"
                        autoDetectionTarget = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalArgumentException) {
                        capture.bitmap.recycle()
                        detectorProcessing = false
                        val message = error.message ?: "馬賽克偵測輸入格式不符"
                        autoDetectionTarget = null
                        detectorError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    }
                    capture.bitmap.recycle()
                    detectorProcessing = false
                    if (config.processOnlyWhenPaused && player.isPlaying) {
                        regionTracker.reset()
                        autoDetectionTarget = null
                        lastProcessedPositionMs = null
                        continue
                    }
                    val detected = detection.region?.toNormalizedRegion()
                    val trackedRegion = regionTracker.update(detected)
                    val target = when {
                        trackedRegion == null -> null
                        detection.mask != null && detected != null -> {
                            AutoDetectionTarget(
                                region = trackedRegion,
                                mask = detection.mask,
                                positionMs = detectionPositionMs
                            )
                        }
                        else -> null
                    }
                    autoDetectionTarget = target
                    if (target == null) {
                        pendingFeedback = MosaicRestorationFeedback.NoMosaicDetected
                    } else if (
                        pendingFeedback is MosaicRestorationFeedback.NoMosaicDetected
                    ) {
                        pendingFeedback = null
                    }
                    lastProcessedPositionMs = detectionPositionMs
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

    val latestAutoDetectionTarget by rememberUpdatedState(autoDetectionTarget)

    LaunchedEffect(
        playerView,
        player,
        config.enabled,
        config.processOnlyWhenPaused,
        config.region,
        autoDetectionConfig.enabled,
        autoDetectionConfig.threshold,
        processingRequestId,
        model,
        restorerState,
        isRegionEditing
    ) {
        frameCaptureProcessing = false
        capturedFrameCount = 0
        totalFrameCount = 0
        restorationProcessing = false
        displayedSourceFrame = null
        restoredPreview = null
        captureError = null
        val readyState = restorerState as? RestorerState.Ready ?: return@LaunchedEffect
        if (!config.enabled || model == null) return@LaunchedEffect

        readyState.restorer.reset()
        var consecutiveCaptureFailures = 0
        var lastProcessedPositionMs: Long? = null
        var lastInferenceRegion: NormalizedRegion? = null
        var temporalStateActive = false
        var trackedMediaItem = player.currentMediaItem

        suspend fun clearTemporalState() {
            if (temporalStateActive) {
                readyState.restorer.reset()
                temporalStateActive = false
            }
            lastProcessedPositionMs = null
            lastInferenceRegion = null
        }

        while (isActive) {
            val currentMediaItem = player.currentMediaItem
            if (currentMediaItem !== trackedMediaItem) {
                displayedSourceFrame = null
                restoredPreview = null
                pendingFeedback = null
                clearTemporalState()
                trackedMediaItem = currentMediaItem
            }
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                displayedSourceFrame = null
                restoredPreview = null
                clearTemporalState()
                delay(PausedPreviewDelayMs)
                continue
            }
            if (isRegionEditing) {
                displayedSourceFrame = null
                restoredPreview = null
                pendingFeedback = null
                clearTemporalState()
                delay(PausedPreviewDelayMs)
                continue
            }
            if (config.processOnlyWhenPaused && player.isPlaying) {
                displayedSourceFrame = null
                restoredPreview = null
                clearTemporalState()
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
                displayedSourceFrame = null
                restoredPreview = null
                clearTemporalState()
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (player.playbackState == Player.STATE_BUFFERING) {
                if (config.processOnlyWhenPaused) {
                    displayedSourceFrame = null
                    restoredPreview = null
                }
                delay(PlayerNotReadyDelayMs)
                continue
            }
            if (!config.processOnlyWhenPaused && !player.isPlaying) {
                delay(PausedPreviewDelayMs)
                continue
            }
            if (!player.isPlaying &&
                lastProcessedPositionMs?.isNearPosition(player.currentPosition) == true
            ) {
                delay(PausedPreviewDelayMs)
                continue
            }

            val currentAutoTarget = if (autoDetectionConfig.enabled) {
                latestAutoDetectionTarget?.takeIf { target ->
                    val maximumGap = if (player.isPlaying) {
                        MaximumPlayingDetectionTargetGapMs
                    } else {
                        SeekPositionToleranceMs
                    }
                    abs(target.positionMs - player.currentPosition) <= maximumGap
                }
            } else {
                null
            }
            val safeRegion = currentAutoTarget?.region
                ?: if (autoDetectionConfig.enabled) null else config.region.sanitized()
            if (safeRegion == null) {
                if (config.processOnlyWhenPaused) {
                    displayedSourceFrame = null
                    restoredPreview = null
                }
                clearTemporalState()
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

            val inferenceRegion = calculateSquareRestorationRegion(
                region = safeRegion,
                videoWidth = videoSize.width,
                videoHeight = videoSize.height
            )
            val sourceRect = calculateRestorationSourceRegion(
                region = inferenceRegion,
                videoWidth = videoSize.width,
                videoHeight = videoSize.height
            ).toRect()
            val textureSourceRect = inferenceRegion.toPixelRect(
                width = videoSurface.width,
                height = videoSurface.height
            )
            val alphaMask = currentAutoTarget?.mask?.let { mask ->
                createFeatheredMosaicMask(
                    mask = mask,
                    regionLeft = inferenceRegion.left,
                    regionTop = inferenceRegion.top,
                    regionRight = inferenceRegion.right,
                    regionBottom = inferenceRegion.bottom,
                    threshold = autoDetectionConfig.threshold,
                    outputSize = model.inputSize
                )
            }

            frameCaptureProcessing = true
            capturedFrameCount = 0
            totalFrameCount = model.temporalFrameCount
            val capture = try {
                temporalCaptureMutex.lock()
                try {
                    captureVideoFrameSequence(
                        player = player,
                        videoSurface = videoSurface,
                        sourceRect = sourceRect,
                        textureSourceRect = textureSourceRect,
                        destinationSize = model.inputSize,
                        frameCount = model.temporalFrameCount,
                        handler = mainHandler,
                        frameIntervalMs = calculateDeepMosaicsFrameIntervalMs(
                            player.videoFormat?.frameRate
                        ),
                        captureDisplayFrame = !config.processOnlyWhenPaused,
                        onFrameCaptured = { captured, total ->
                            capturedFrameCount = captured
                            totalFrameCount = total
                        }
                    )
                } finally {
                    temporalCaptureMutex.unlock()
                }
            } finally {
                frameCaptureProcessing = false
            }
            when (capture) {
                FrameSequenceCaptureResult.NoFrame -> {
                    consecutiveCaptureFailures += 1
                    if (consecutiveCaptureFailures >= MaxTransientCaptureFailures) {
                        val message = "無法擷取 DeepMosaics 所需的時序影格"
                        Log.w(Tag, message)
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    }
                    delay(PlayerNotReadyDelayMs)
                }

                is FrameSequenceCaptureResult.Error -> {
                    restoredPreview = null
                    captureError = capture.message
                    latestOnError(capture.message)
                    return@LaunchedEffect
                }

                is FrameSequenceCaptureResult.Success -> {
                    val nextSourceFrame = capture.displayFrame
                    if (!config.processOnlyWhenPaused && nextSourceFrame == null) {
                        capture.frames.recycleAll()
                        val message = "連續 AI 播放缺少完整來源影格"
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    }
                    consecutiveCaptureFailures = 0
                    val previousPosition = lastProcessedPositionMs
                    val previousRegion = lastInferenceRegion
                    if ((previousPosition != null &&
                            abs(capture.centerPositionMs - previousPosition) >
                            MaximumAutoregressiveGapMs) ||
                        (previousRegion != null &&
                            regionIntersectionOverUnion(previousRegion, inferenceRegion) <
                            MinimumAutoregressiveRegionOverlap)
                    ) {
                        readyState.restorer.reset()
                        temporalStateActive = false
                    }
                    restorationProcessing = true
                    val restored = try {
                        readyState.restorer.restore(
                            frames = capture.frames,
                            alphaMask = alphaMask
                        )
                    } catch (error: CancellationException) {
                        capture.frames.recycleAll()
                        nextSourceFrame?.recycleSafely()
                        throw error
                    } catch (error: OrtException) {
                        capture.frames.recycleAll()
                        nextSourceFrame?.recycleSafely()
                        val message = error.message ?: "AI 推論失敗"
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalStateException) {
                        capture.frames.recycleAll()
                        nextSourceFrame?.recycleSafely()
                        val message = error.message ?: "AI 模型輸出格式不符"
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } catch (error: IllegalArgumentException) {
                        capture.frames.recycleAll()
                        nextSourceFrame?.recycleSafely()
                        val message = error.message ?: "AI 模型輸入格式不符"
                        restoredPreview = null
                        captureError = message
                        latestOnError(message)
                        return@LaunchedEffect
                    } finally {
                        restorationProcessing = false
                    }
                    capture.frames.recycleAll()
                    temporalStateActive = true
                    if (config.processOnlyWhenPaused && player.isPlaying) {
                        nextSourceFrame?.recycleSafely()
                        restored.bitmap.recycle()
                        displayedSourceFrame = null
                        restoredPreview = null
                        clearTemporalState()
                        continue
                    }
                    if (config.processOnlyWhenPaused) {
                        nextSourceFrame?.recycleSafely()
                        displayedSourceFrame = null
                    } else {
                        displayedSourceFrame = nextSourceFrame
                    }
                    restoredPreview = RestorationPreview(
                        image = restored,
                        region = inferenceRegion
                    )
                    pendingFeedback = MosaicRestorationFeedback.Completed(
                        inferenceDurationMs = restored.inferenceDurationMs,
                        modelChangeFraction = restored.changeFraction,
                        strength = config.strength
                    )
                    captureError = null
                    lastProcessedPositionMs = capture.centerPositionMs
                    lastInferenceRegion = inferenceRegion
                    if (!config.processOnlyWhenPaused && autoDetectionConfig.enabled) {
                        autoDetectionTarget = null
                    }

                    val nextFrameDelay = if (config.processOnlyWhenPaused) {
                        max(
                            MinimumPreviewIntervalMs,
                            (restored.inferenceDurationMs * InferenceCooldownMultiplier).toLong()
                        )
                    } else {
                        ContinuousPlaybackCooldownMs
                    }
                    delay(nextFrameDelay)
                }
            }
        }
    }

    Box(modifier = modifier) {
        val bounds = surfaceBounds
        val sourceFrame = displayedSourceFrame
        val preview = restoredPreview
        val visibleError = detectorError
            ?: captureError
            ?: (detectorState as? DetectorState.Error)?.message
            ?: (restorerState as? RestorerState.Error)?.message
        DisposableEffect(sourceFrame) {
            onDispose {
                sourceFrame?.recycleSafely()
            }
        }
        DisposableEffect(preview?.image?.bitmap) {
            val bitmap = preview?.image?.bitmap
            onDispose {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        if (config.enabled && !config.processOnlyWhenPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
            if (bounds != null && sourceFrame != null) {
                FrozenVideoFrame(
                    bitmap = sourceFrame,
                    bounds = bounds
                )
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
                        "DeepMosaics 處理完成 · " +
                            "${formatMosaicInferenceDuration(preview.image.inferenceDurationMs)}" +
                            " · 模型變化 " +
                            formatMosaicChangeFraction(preview.image.changeFraction) +
                            " · 混合 ${formatMosaicStrength(config.strength)}" +
                            "（自動偵測，推測畫面）"
                    } else {
                        "DeepMosaics 處理完成 · " +
                            "${formatMosaicInferenceDuration(preview.image.inferenceDurationMs)}" +
                            " · 模型變化 " +
                            formatMosaicChangeFraction(preview.image.changeFraction) +
                            " · 混合 ${formatMosaicStrength(config.strength)}" +
                            "（手動框選，推測畫面）"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        val selectedProcessingRegion = if (autoDetectionConfig.enabled) {
            autoDetectionTarget?.region
        } else {
            config.region.sanitized()
        }
        val videoSize = player.videoSize
        val displayedProcessingRegion = preview?.region ?: selectedProcessingRegion?.let { region ->
            if (videoSize.width > 0 && videoSize.height > 0) {
                calculateSquareRestorationRegion(
                    region = region,
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height
                )
            } else {
                region
            }
        }
        if (config.enabled &&
            config.showProcessingRegion &&
            bounds != null &&
            displayedProcessingRegion != null &&
            !isRegionEditing
        ) {
            ProcessingRegionIndicator(
                bounds = bounds,
                region = displayedProcessingRegion,
                isAutomatic = autoDetectionConfig.enabled,
                modifier = Modifier.fillMaxSize()
            )
        }

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
        } else if (config.enabled && activeFeedback != null) {
            RestorationFeedbackCard(
                feedback = activeFeedback,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
            )
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
private fun FrozenVideoFrame(
    bitmap: Bitmap,
    bounds: SurfaceBounds
) {
    val density = LocalDensity.current
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset { IntOffset(bounds.left, bounds.top) }
            .size(
                width = with(density) { bounds.width.toDp() },
                height = with(density) { bounds.height.toDp() }
            )
    )
}

@Composable
private fun ProcessingRegionIndicator(
    bounds: SurfaceBounds,
    region: NormalizedRegion,
    isAutomatic: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val target = bounds.regionRect(region)
    val color = if (isAutomatic) Color(0xffffd54f) else Color(0xff64ddff)
    val labelOffsetY = with(density) {
        (target.top - 34.dp.roundToPx()).coerceAtLeast(0)
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(target.left.toFloat(), target.top.toFloat()),
                size = Size(target.width().toFloat(), target.height().toFloat())
            )
            drawRect(
                color = color,
                topLeft = Offset(target.left.toFloat(), target.top.toFloat()),
                size = Size(target.width().toFloat(), target.height().toFloat()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
            )
        }
        Card(
            modifier = Modifier.offset {
                IntOffset(target.left, labelOffsetY)
            },
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.78f)
            )
        ) {
            Text(
                text = if (isAutomatic) "自動偵測處理範圍" else "手動處理範圍",
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RestorationFeedbackCard(
    feedback: MosaicRestorationFeedback,
    modifier: Modifier = Modifier
) {
    val isCompleted = feedback is MosaicRestorationFeedback.Completed
    val isNoMosaic = feedback is MosaicRestorationFeedback.NoMosaicDetected
    val hasNoVisibleChange =
        feedback is MosaicRestorationFeedback.Completed && !feedback.hasVisibleChange
    val containerColor = when {
        hasNoVisibleChange -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)
        isCompleted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
        isNoMosaic -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)
        else -> Color.Black.copy(alpha = 0.78f)
    }
    val contentColor = when {
        hasNoVisibleChange -> MaterialTheme.colorScheme.onSecondaryContainer
        isCompleted -> MaterialTheme.colorScheme.onPrimaryContainer
        isNoMosaic -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> Color.White
    }
    val progress = feedback.progressFraction

    Card(
        modifier = modifier.width(320.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (feedback.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = feedback.displayMessage(),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (feedback.isBusy && progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = contentColor,
                    trackColor = contentColor.copy(alpha = 0.25f)
                )
            }
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

private suspend fun captureVideoFrameSequence(
    player: ExoPlayer,
    videoSurface: View,
    sourceRect: Rect,
    textureSourceRect: Rect,
    destinationSize: Int,
    frameCount: Int,
    handler: Handler,
    frameIntervalMs: Long,
    captureDisplayFrame: Boolean,
    onFrameCaptured: (captured: Int, total: Int) -> Unit
): FrameSequenceCaptureResult {
    require(destinationSize > 0) { "Destination size must be positive" }
    require(frameCount > 0 && frameCount % 2 == 1) {
        "Temporal frame count must be a positive odd number"
    }
    require(frameIntervalMs > 0L) { "Frame interval must be positive" }

    if (!player.isPlaying) {
        if (captureDisplayFrame) {
            return FrameSequenceCaptureResult.NoFrame
        }
        return capturePausedVideoFrameSequence(
            player = player,
            videoSurface = videoSurface,
            sourceRect = sourceRect,
            textureSourceRect = textureSourceRect,
            destinationSize = destinationSize,
            frameCount = frameCount,
            frameIntervalMs = frameIntervalMs,
            handler = handler,
            onFrameCaptured = onFrameCaptured
        )
    }

    val frames = ArrayList<Bitmap>(frameCount)
    var transferred = false
    var centerPosition = player.currentPosition
    var displayFrame: Bitmap? = null
    try {
        repeat(frameCount) { index ->
            when (
                val capture = captureVideoRegion(
                    videoSurface = videoSurface,
                    sourceRect = sourceRect,
                    textureSourceRect = textureSourceRect,
                    destinationWidth = destinationSize,
                    destinationHeight = destinationSize,
                    handler = handler
                )
            ) {
                CaptureResult.NoFrame -> return FrameSequenceCaptureResult.NoFrame
                is CaptureResult.Error -> {
                    return FrameSequenceCaptureResult.Error(capture.message)
                }
                is CaptureResult.Success -> {
                    frames += capture.bitmap
                    onFrameCaptured(frames.size, frameCount)
                    if (index == frameCount / 2) {
                        centerPosition = player.currentPosition
                        if (captureDisplayFrame) {
                            when (
                                val fullFrame = captureVideoFrame(
                                    videoSurface = videoSurface,
                                    destinationWidth = videoSurface.width,
                                    destinationHeight = videoSurface.height,
                                    handler = handler
                                )
                            ) {
                                CaptureResult.NoFrame -> {
                                    return FrameSequenceCaptureResult.Error(
                                        "無法擷取連續 AI 播放的完整影格"
                                    )
                                }
                                is CaptureResult.Error -> {
                                    return FrameSequenceCaptureResult.Error(fullFrame.message)
                                }
                                is CaptureResult.Success -> {
                                    displayFrame = fullFrame.bitmap
                                }
                            }
                        }
                    }
                }
            }

            if (index < frameCount - 1) {
                if (!player.isPlaying) {
                    return FrameSequenceCaptureResult.NoFrame
                }
                delay(frameIntervalMs)
            }
        }

        transferred = true
        return FrameSequenceCaptureResult.Success(
            frames = frames,
            centerPositionMs = centerPosition,
            displayFrame = displayFrame
        )
    } finally {
        if (!transferred) {
            frames.recycleAll()
            displayFrame?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }
}

private suspend fun capturePausedVideoFrameSequence(
    player: ExoPlayer,
    videoSurface: View,
    sourceRect: Rect,
    textureSourceRect: Rect,
    destinationSize: Int,
    frameCount: Int,
    frameIntervalMs: Long,
    handler: Handler,
    onFrameCaptured: (captured: Int, total: Int) -> Unit
): FrameSequenceCaptureResult {
    if (!player.isCurrentMediaItemSeekable) {
        return FrameSequenceCaptureResult.Error(
            "此串流不支援搜尋，無法在暫停時擷取前後影格；" +
                "請關閉「只在影片暫停時處理」並播放影片後再試"
        )
    }

    return capturePausedVideoFrameSequenceLocked(
        player = player,
        videoSurface = videoSurface,
        sourceRect = sourceRect,
        textureSourceRect = textureSourceRect,
        destinationSize = destinationSize,
        frameCount = frameCount,
        frameIntervalMs = frameIntervalMs,
        handler = handler,
        onFrameCaptured = onFrameCaptured
    )
}

private suspend fun capturePausedVideoFrameSequenceLocked(
    player: ExoPlayer,
    videoSurface: View,
    sourceRect: Rect,
    textureSourceRect: Rect,
    destinationSize: Int,
    frameCount: Int,
    frameIntervalMs: Long,
    handler: Handler,
    onFrameCaptured: (captured: Int, total: Int) -> Unit
): FrameSequenceCaptureResult {
    val centerPositionMs = player.currentPosition.coerceAtLeast(0L)
    val durationMs = player.duration.takeIf { it > 0L }
    val samplePositions = calculateDeepMosaicsSamplePositions(
        centerPositionMs = centerPositionMs,
        durationMs = durationMs,
        frameIntervalMs = frameIntervalMs,
        frameCount = frameCount
    )
    val frames = ArrayList<Bitmap>(frameCount)
    val originalSeekParameters = player.seekParameters
    var transferred = false
    var issuedSeek = false

    try {
        player.setSeekParameters(SeekParameters.EXACT)
        samplePositions.forEachIndexed { index, positionMs ->
            if (index > 0 && positionMs == samplePositions[index - 1]) {
                frames += frames.last()
                onFrameCaptured(frames.size, frameCount)
                return@forEachIndexed
            }

            if (!player.currentPosition.isNearPosition(positionMs)) {
                issuedSeek = true
                if (!seekToPausedVideoFrame(player, positionMs)) {
                    return FrameSequenceCaptureResult.Error(
                        "無法讀取影片前後影格；請確認串流可搜尋，" +
                            "或關閉「只在影片暫停時處理」並播放影片後再試"
                    )
                }
            }

            when (
                val capture = captureVideoRegionWithRetries(
                    videoSurface = videoSurface,
                    sourceRect = sourceRect,
                    textureSourceRect = textureSourceRect,
                    destinationSize = destinationSize,
                    handler = handler
                )
            ) {
                CaptureResult.NoFrame -> {
                    return FrameSequenceCaptureResult.Error(
                        "影片前後影格尚未準備完成，請稍後再按一次魔法棒"
                    )
                }
                is CaptureResult.Error -> {
                    return FrameSequenceCaptureResult.Error(capture.message)
                }
                is CaptureResult.Success -> {
                    frames += capture.bitmap
                    onFrameCaptured(frames.size, frameCount)
                }
            }
        }

        if (issuedSeek && !player.currentPosition.isNearPosition(centerPositionMs)) {
            if (!seekToPausedVideoFrame(player, centerPositionMs)) {
                return FrameSequenceCaptureResult.Error(
                    "已擷取時序影格，但播放器無法回到原本位置"
                )
            }
        }

        transferred = true
        return FrameSequenceCaptureResult.Success(
            frames = frames,
            centerPositionMs = centerPositionMs,
            displayFrame = null
        )
    } finally {
        if (issuedSeek && !player.currentPosition.isNearPosition(centerPositionMs)) {
            player.seekTo(centerPositionMs)
        }
        player.setSeekParameters(originalSeekParameters)
        if (!transferred) {
            frames.recycleAll()
        }
    }
}

private suspend fun seekToPausedVideoFrame(
    player: ExoPlayer,
    positionMs: Long
): Boolean {
    val frameRendered = withTimeoutOrNull(SeekFrameTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            lateinit var listener: Player.Listener
            fun complete(success: Boolean) {
                player.removeListener(listener)
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }

            listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    complete(player.currentPosition.isNearPosition(positionMs))
                }

                override fun onPlayerError(error: PlaybackException) {
                    complete(false)
                }
            }

            player.addListener(listener)
            continuation.invokeOnCancellation {
                player.removeListener(listener)
            }
            player.seekTo(positionMs)
        }
    } ?: false
    if (frameRendered) {
        delay(SurfaceLatchDelayMs)
    }
    return frameRendered
}

private suspend fun captureVideoRegionWithRetries(
    videoSurface: View,
    sourceRect: Rect,
    textureSourceRect: Rect,
    destinationSize: Int,
    handler: Handler
): CaptureResult {
    repeat(PausedFrameCaptureAttempts) { attempt ->
        val capture = captureVideoRegion(
            videoSurface = videoSurface,
            sourceRect = sourceRect,
            textureSourceRect = textureSourceRect,
            destinationWidth = destinationSize,
            destinationHeight = destinationSize,
            handler = handler
        )
        if (capture != CaptureResult.NoFrame) {
            return capture
        }
        if (attempt < PausedFrameCaptureAttempts - 1) {
            delay(PausedFrameCaptureRetryDelayMs)
        }
    }
    return CaptureResult.NoFrame
}

private fun Long.isNearPosition(other: Long): Boolean {
    return abs(this - other) <= SeekPositionToleranceMs
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

private data class AutoDetectionTarget(
    val region: NormalizedRegion,
    val mask: MosaicProbabilityMask,
    val positionMs: Long
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

private sealed interface FrameSequenceCaptureResult {
    data class Success(
        val frames: List<Bitmap>,
        val centerPositionMs: Long,
        val displayFrame: Bitmap?
    ) : FrameSequenceCaptureResult

    data object NoFrame : FrameSequenceCaptureResult
    data class Error(val message: String) : FrameSequenceCaptureResult
}

private fun List<Bitmap>.recycleAll() {
    forEach { bitmap ->
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

private fun Bitmap.recycleSafely() {
    if (!isRecycled) {
        recycle()
    }
}

private sealed interface RestorerState {
    data object Unavailable : RestorerState
    data object Loading : RestorerState
    data class Ready(val restorer: OnnxMosaicRestorer) : RestorerState
    data class Error(val message: String) : RestorerState
}

private sealed interface DetectorState {
    data object Unavailable : DetectorState
    data object Loading : DetectorState
    data class Ready(val detector: OnnxMosaicDetector) : DetectorState
    data class Error(val message: String) : DetectorState
}

private const val Tag = "MosaicRestoration"
private const val GeometryRefreshMs = 100L
private const val IdleGeometryRefreshMs = 500L
private const val PlayerNotReadyDelayMs = 250L
private const val PausedPreviewDelayMs = 500L
private const val MinimumPreviewIntervalMs = 180L
private const val ContinuousPlaybackCooldownMs = 50L
private const val InferenceCooldownMultiplier = 0.25f
private const val MaximumAutoregressiveGapMs = 750L
private const val MaximumPlayingDetectionTargetGapMs = 5_000L
private const val MinimumAutoregressiveRegionOverlap = 0.55f
private const val MinimumDetectionIntervalMs = 750L
private const val DetectionCooldownMultiplier = 1.25f
private const val MaxTransientCaptureFailures = 12
private const val FeedbackResultVisibilityMs = 5_000L
private const val SeekFrameTimeoutMs = 3_000L
private const val SeekPositionToleranceMs = 2L
private const val SurfaceLatchDelayMs = 32L
private const val PausedFrameCaptureAttempts = 3
private const val PausedFrameCaptureRetryDelayMs = 80L
private val temporalCaptureMutex = Mutex()
