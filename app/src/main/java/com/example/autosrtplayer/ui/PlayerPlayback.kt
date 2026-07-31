package com.example.autosrtplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ClipboardManager
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.autosrtplayer.ui.vr.VrPlayerSurface
import com.example.autosrtplayer.ui.vr.VrSubtitleOverlay
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GestureHudTimeoutMs = 900L
private const val FullscreenControlsAutoHideMs = 2500L
private const val SubtitleBackgroundAlpha = 0x66
private const val MinBrightness = 0.05f
private const val ExtraDimThreshold = 0.2f
private const val MaxExtraDimAlpha = 0.85f
private const val SeekMaxOffsetMs = 180_000L
private const val SeekStepMs = 60_000L
private const val CenterButtonSize = 72
private const val ControlOverlayAlpha = 0.14f
private const val ScrubberOverlayAlpha = 0.10f
private const val GestureHudAlpha = 0.08f
private const val MinControlsContentAlpha = 0.18f
private const val MinSpeedControlAlpha = 0.42f
private const val SpeedMenuContainerAlpha = 0.94f
private const val SpeedMenuBorderAlpha = 0.36f
private const val SpeedMenuItemPaddingVertical = 10
private const val VrRotationSensitivity = 0.4f
private val PlaybackSpeedOptions = listOf(0.5f, 1f, 2f, 4f, 8f)

private enum class OverlayGestureMode {
    Seek,
    Brightness,
    Volume
}

private data class GestureHudState(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val valueText: String? = null,
    val progress: Float? = null
)

private data class PlaybackProgressState(
    val currentPositionMs: Long,
    val durationMs: Long
)

private data class BrightnessState(
    val gestureValue: Float,
    val screenBrightness: Float,
    val overlayAlpha: Float
)

@androidx.media3.common.util.UnstableApi
@Composable
internal fun InlinePlayer(
    player: ExoPlayer?,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    onToggleFullscreen: () -> Unit
) {
    if (player == null) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    applySubtitleStyle()
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.player = player
                it.applySubtitleStyle()
            }
        )

        PlaybackSpeedButton(
            playbackSpeed = playbackSpeed,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )

        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
                .size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = "Enter fullscreen",
                tint = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
internal fun FullscreenPlayer(
    activity: Activity?,
    player: ExoPlayer?,
    playbackSpeed: Float,
    screenOrientationMode: ScreenOrientationMode,
    vrConfig: VrPlaybackConfig,
    vrViewAngles: VrViewAngles,
    isVrHeadTrackingEnabled: Boolean,
    currentSourceId: String?,
    currentRequestLabel: String?,
    isCurrentFavorite: Boolean,
    canToggleFavorite: Boolean,
    favoriteCount: Int,
    isLoading: Boolean,
    loadingStage: LoadingStage,
    errorMessage: String?,
    onPlaybackSpeedChange: (Float) -> Unit,
    onToggleScreenOrientationMode: () -> Unit,
    onVrViewDrag: (Float, Float) -> Unit,
    onResetVrView: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenTodayHot: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onSubmitSourceId: (String) -> Unit
) {
    var hudState by remember { mutableStateOf<GestureHudState?>(null) }
    var appBrightness by rememberSaveable { mutableStateOf<Float?>(null) }
    var dimOverlayAlpha by rememberSaveable { mutableStateOf(0f) }
    val progressState = rememberPlaybackProgressState(player)
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var controlsInteractionTick by remember(player) { mutableLongStateOf(0L) }
    var isScrubbing by remember(player) { mutableStateOf(false) }
    var scrubPositionMs by remember(player) { mutableLongStateOf(0L) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var sourceDraft by rememberSaveable { mutableStateOf("") }
    val displayedPositionMs = if (isScrubbing) scrubPositionMs else progressState.currentPositionMs
    val latestPlayer by rememberUpdatedState(player)
    val density = LocalDensity.current

    fun pingControls() {
        controlsInteractionTick += 1
    }

    DisposableEffect(activity, appBrightness) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val brightnessValue = appBrightness
        if (brightnessValue != null) {
            val brightnessState = mapBrightnessState(brightnessValue)
            dimOverlayAlpha = brightnessState.overlayAlpha
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = brightnessState.screenBrightness
                it.attributes = attributes
            }
        } else {
            dimOverlayAlpha = 0f
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = attributes
            }
        }
        onDispose {
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = originalBrightness
                it.attributes = attributes
            }
        }
    }

    LaunchedEffect(hudState) {
        if (hudState != null) {
            delay(GestureHudTimeoutMs)
            hudState = null
        }
    }

    LaunchedEffect(controlsVisible, isScrubbing, controlsInteractionTick) {
        if (controlsVisible && !isScrubbing) {
            delay(FullscreenControlsAutoHideMs)
            controlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        val controlsContentAlpha = (1f - dimOverlayAlpha).coerceIn(MinControlsContentAlpha, 1f)
        val speedControlAlpha = (1f - dimOverlayAlpha).coerceIn(MinSpeedControlAlpha, 1f)
        val widthPx = with(density) { maxWidth.toPx() }.takeIf { it > 0f } ?: 1f
        val heightPx = with(density) { maxHeight.toPx() }.takeIf { it > 0f } ?: 1f

        val isVrMode = vrConfig.contentMode == VrContentMode.Vr

        // Only initialize VR components when we have actual media to play and valid config
        val hasMedia = player != null && player.currentMediaItem != null
        val canInitializeVr = isVrMode && hasMedia && vrConfig.isValid()

        val headTrackingState = if (canInitializeVr) {
            com.example.autosrtplayer.ui.vr.rememberVrHeadTrackingState(
                enabled = isVrHeadTrackingEnabled,
                config = vrConfig
            )
        } else {
            null
        }

        if (player != null) {
            if (canInitializeVr && headTrackingState != null) {
                val effectiveVrViewAngles = com.example.autosrtplayer.ui.vr.combineVrAngles(
                    manual = vrViewAngles,
                    sensorOffset = headTrackingState.offset,
                    config = vrConfig
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    VrPlayerSurface(
                        player = player,
                        config = vrConfig,
                        viewAngles = effectiveVrViewAngles,
                        modifier = Modifier.fillMaxSize()
                    )
                    VrSubtitleOverlay(
                        player = player,
                        config = vrConfig,
                        modifier = Modifier.fillMaxSize()
                    )

                    VrGestureLayer(
                        manualViewAngles = vrViewAngles,
                        onVrViewDrag = onVrViewDrag,
                        onToggleControls = {
                            if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                controlsVisible = true
                                pingControls()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            applySubtitleStyle()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = {
                        it.player = player
                        it.applySubtitleStyle()
                    }
                )
            }
        }

        if (dimOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = dimOverlayAlpha))
            )
        }

        if (!controlsVisible && !isVrMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .alpha(controlsContentAlpha)
            ) {
                val handleSeekChange: (Long, Long) -> Unit = { deltaMs, targetMs ->
                    hudState = GestureHudState(
                        icon = if (deltaMs >= 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                        label = if (deltaMs >= 0) "快轉" else "倒退",
                        valueText = "${abs(deltaMs) / 1000}秒 · ${formatDuration(targetMs)}"
                    )
                }

                GestureZone(
                    modifier = Modifier
                        .weight(0.24f)
                        .fillMaxHeight(),
                    mode = OverlayGestureMode.Brightness,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    player = latestPlayer,
                    currentBrightness = appBrightness,
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) pingControls()
                    },
                    onBrightnessChange = { value ->
                        appBrightness = value
                        val brightnessState = mapBrightnessState(value)
                        dimOverlayAlpha = brightnessState.overlayAlpha
                        activity?.window?.let { window ->
                            val attributes = window.attributes
                            attributes.screenBrightness = brightnessState.screenBrightness
                            window.attributes = attributes
                        }
                        hudState = GestureHudState(
                            icon = Icons.Filled.Brightness6,
                            label = "亮度",
                            valueText = "${(brightnessState.gestureValue * 100).roundToInt()}%",
                            progress = brightnessState.gestureValue
                        )
                    },
                    onVolumeChange = { _, _ -> },
                    onSeekChange = handleSeekChange
                )

                GestureZone(
                    modifier = Modifier
                        .weight(0.52f)
                        .fillMaxHeight(),
                    mode = OverlayGestureMode.Seek,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    player = latestPlayer,
                    currentBrightness = appBrightness,
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) pingControls()
                    },
                    onBrightnessChange = { },
                    onVolumeChange = { _, _ -> },
                    onSeekChange = handleSeekChange
                )

                GestureZone(
                    modifier = Modifier
                        .weight(0.24f)
                        .fillMaxHeight(),
                    mode = OverlayGestureMode.Volume,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    player = latestPlayer,
                    currentBrightness = appBrightness,
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) pingControls()
                    },
                    onBrightnessChange = { },
                    onVolumeChange = { current, max ->
                        val progress = if (max > 0) current / max.toFloat() else 0f
                        hudState = GestureHudState(
                            icon = if (current == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            label = "音量",
                            valueText = "${(progress * 100).roundToInt()}%",
                            progress = progress
                        )
                    },
                    onSeekChange = handleSeekChange
                )
            }
        }

        if (controlsVisible) {
            IconButton(
                onClick = {
                    player?.let {
                        if (it.isPlaying) it.pause() else it.play()
                        pingControls()
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = ControlOverlayAlpha), shape = androidx.compose.foundation.shape.CircleShape)
                    .size(CenterButtonSize.dp)
                    .alpha(controlsContentAlpha)
            ) {
                val isPlaying = player?.isPlaying == true
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .alpha(controlsContentAlpha),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentSourceId ?: currentRequestLabel ?: "輸入 ID",
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(end = 4.dp)
                )
                IconButton(
                    onClick = {
                        sourceDraft = currentSourceId.orEmpty()
                        showSourceDialog = true
                        pingControls()
                    },
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit source ID",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
                IconButton(
                    onClick = {
                        onOpenTodayHot()
                        pingControls()
                    },
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Whatshot,
                        contentDescription = "熱門",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
                IconButton(
                    onClick = {
                        onOpenFavorites()
                        pingControls()
                    },
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmarks,
                        contentDescription = "最愛 ($favoriteCount)",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
                IconButton(
                    onClick = {
                        onOpenSettings()
                        pingControls()
                    },
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "設定",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            }

            PlaybackSpeedButton(
                playbackSpeed = playbackSpeed,
                onPlaybackSpeedChange = {
                    onPlaybackSpeedChange(it)
                    pingControls()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .alpha(speedControlAlpha),
                overlayAlpha = ControlOverlayAlpha,
                contentAlpha = speedControlAlpha,
                onExpandedChange = { pingControls() }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .alpha(controlsContentAlpha),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        pingControls()
                        onToggleFavorite()
                    },
                    enabled = canToggleFavorite,
                    modifier = Modifier
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = ControlOverlayAlpha), shape = MaterialTheme.shapes.small)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isCurrentFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isCurrentFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (canToggleFavorite) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.38f)
                    )
                }
            }

            val orientationIcon = when (screenOrientationMode) {
                ScreenOrientationMode.Auto -> Icons.Filled.ScreenRotation
                ScreenOrientationMode.Portrait -> Icons.Filled.ScreenLockPortrait
                ScreenOrientationMode.Landscape -> Icons.Filled.ScreenLockLandscape
            }
            val orientationDescription = when (screenOrientationMode) {
                ScreenOrientationMode.Auto -> "螢幕方向：自動旋轉"
                ScreenOrientationMode.Portrait -> "螢幕方向：直向鎖定"
                ScreenOrientationMode.Landscape -> "螢幕方向：橫向鎖定"
            }

            IconButton(
                onClick = {
                    pingControls()
                    if (isVrMode) {
                        onResetVrView()
                        headTrackingState?.recenter()
                    } else {
                        onToggleScreenOrientationMode()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 84.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = ControlOverlayAlpha), shape = MaterialTheme.shapes.small)
                    .size(48.dp)
                    .alpha(controlsContentAlpha)
            ) {
                Icon(
                    imageVector = if (isVrMode) Icons.Filled.ScreenRotation else orientationIcon,
                    contentDescription = if (isVrMode) "重設視角" else orientationDescription,
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }

            FullscreenScrubber(
                currentPositionMs = displayedPositionMs,
                durationMs = progressState.durationMs,
                onValueChange = { value ->
                    if (!isScrubbing) {
                        scrubPositionMs = progressState.currentPositionMs
                    }
                    isScrubbing = true
                    scrubPositionMs = value
                    pingControls()
                },
                onValueChangeFinished = {
                    val duration = progressState.durationMs
                    if (duration > 0L) {
                        val target = scrubPositionMs.coerceIn(0L, duration)
                        player?.seekTo(target)
                        scrubPositionMs = target
                    }
                    isScrubbing = false
                    pingControls()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .alpha(controlsContentAlpha)
            )
        }

        if (player == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(controlsContentAlpha),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "輸入 ID、選擇熱門或我的最愛開始播放",
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }

        if (isLoading) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    val stageLabel = when (loadingStage) {
                        LoadingStage.ResolvingId -> "正在解析 ID…"
                        LoadingStage.FetchingPlaylist -> "正在取得播放清單…"
                        LoadingStage.ResolvingSource -> "解析中…"
                        LoadingStage.BuildingPlayer -> "播放器初始化中…"
                        LoadingStage.Idle -> "載入中…"
                    }
                    Text(stageLabel, color = androidx.compose.ui.graphics.Color.White)
                    currentRequestLabel?.let {
                        Text("來源：$it", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }

        errorMessage?.let { message ->
            val context = LocalContext.current
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 96.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("錯誤訊息", message)
                                clipboard?.setPrimaryClip(clip)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("複製錯誤訊息")
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.small)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "前往設定",
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }
            }
        }

        hudState?.let {
            GestureHud(
                state = it,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(controlsContentAlpha)
            )
        }

        if (showSourceDialog) {
            AlertDialog(
                onDismissRequest = { showSourceDialog = false },
                title = { Text("輸入新的 ID") },
                text = {
                    OutlinedTextField(
                        value = sourceDraft,
                        onValueChange = { sourceDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("影片 ID") }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val normalized = sourceDraft.trim()
                            if (normalized.isNotBlank()) {
                                onSubmitSourceId(normalized)
                            }
                            showSourceDialog = false
                            pingControls()
                        }
                    ) {
                        Text("確認")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSourceDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun GestureZone(
    modifier: Modifier,
    mode: OverlayGestureMode,
    widthPx: Float,
    heightPx: Float,
    player: ExoPlayer?,
    currentBrightness: Float?,
    onTap: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Int, Int) -> Unit,
    onSeekChange: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var startBrightness by remember { mutableFloatStateOf(resolveInitialBrightness(context as? Activity)) }
    var startVolume by remember { mutableStateOf(0 to 0) }
    var startPositionMs by remember { mutableStateOf(0L) }
    var pendingSeekPositionMs by remember { mutableLongStateOf(0L) }
    var hasPendingSeek by remember { mutableStateOf(false) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .pointerInput(mode, player) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (player == null) return@detectTapGestures
                        val currentPos = player.currentPosition
                        val duration = player.duration
                        if (mode == OverlayGestureMode.Brightness) {
                            val target = (currentPos - SeekStepMs).coerceAtLeast(0L)
                            player.seekTo(target)
                            onSeekChange(-SeekStepMs, target)
                        } else if (mode == OverlayGestureMode.Volume) {
                            val target = if (duration > 0) (currentPos + SeekStepMs).coerceAtMost(duration) else currentPos + SeekStepMs
                            player.seekTo(target)
                            onSeekChange(SeekStepMs, target)
                        }
                    }
                )
            }
            .pointerInput(mode, player, widthPx, heightPx) {
                detectDragGestures(
                    onDragStart = {
                        totalDragX = 0f
                        totalDragY = 0f
                        if (mode == OverlayGestureMode.Brightness) {
                            val activity = context as? Activity
                            startBrightness = currentBrightness ?: resolveInitialBrightness(activity)
                        }
                        if (mode == OverlayGestureMode.Volume) {
                            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                            startVolume = currentVolume to maxVolume
                        }
                        if (mode == OverlayGestureMode.Seek) {
                            startPositionMs = player?.currentPosition ?: 0L
                            pendingSeekPositionMs = startPositionMs
                            hasPendingSeek = false
                        }
                    },
                    onDragEnd = {
                        if (mode == OverlayGestureMode.Seek && hasPendingSeek) {
                            player?.seekTo(pendingSeekPositionMs)
                            onSeekChange(pendingSeekPositionMs - startPositionMs, pendingSeekPositionMs)
                        }
                    },
                    onDragCancel = {
                        if (mode == OverlayGestureMode.Seek) {
                            hasPendingSeek = false
                            pendingSeekPositionMs = startPositionMs
                        }
                    }
                ) { change, dragAmount ->
                    if (player == null) return@detectDragGestures
                    change.consume()
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    if (abs(totalDragX) < abs(totalDragY)) {
                        if (mode == OverlayGestureMode.Brightness) {
                            val newBrightness = (startBrightness - (totalDragY / heightPx)).coerceIn(MinBrightness, 1f)
                            onBrightnessChange(newBrightness)
                        }
                        if (mode == OverlayGestureMode.Volume) {
                            val (currentVolume, maxVolume) = startVolume
                            if (maxVolume > 0) {
                                val delta = (-totalDragY / heightPx * maxVolume).roundToInt()
                                val target = (currentVolume + delta).coerceIn(0, maxVolume)
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                onVolumeChange(target, maxVolume)
                            }
                        }
                    } else if (mode == OverlayGestureMode.Seek) {
                        val duration = player.duration
                        if (duration > 0) {
                            val deltaMs = (totalDragX / widthPx * SeekMaxOffsetMs).roundToInt().toLong()
                            val target = (startPositionMs + deltaMs).coerceIn(0L, duration)
                            pendingSeekPositionMs = target
                            hasPendingSeek = true
                            onSeekChange(deltaMs, target)
                        }
                    }
                }
            }
    )
}

@Composable
private fun GestureHud(
    state: GestureHudState,
    modifier: Modifier = Modifier
) {
    val iconTint = androidx.compose.ui.graphics.Color.White
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = GestureHudAlpha))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = state.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = state.label,
                color = iconTint,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            state.valueText?.let { valueText ->
                Text(valueText, color = iconTint, style = MaterialTheme.typography.bodyMedium)
            }
            state.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.width(120.dp)
                )
            }
        }
    }
}

@Composable
private fun PlaybackSpeedButton(
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    overlayAlpha: Float = ControlOverlayAlpha,
    contentAlpha: Float = 1f,
    onExpandedChange: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    val buttonModifier = modifier
    Box(modifier = buttonModifier) {
        IconButton(
            onClick = {
                expanded = !expanded
                menuVisible = expanded
                onExpandedChange?.invoke()
            },
            modifier = Modifier
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = overlayAlpha), shape = MaterialTheme.shapes.small)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = "播放速度 ${formatPlaybackSpeed(playbackSpeed)}",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = contentAlpha)
            )
        }

        if (menuVisible) {
            Card(
                modifier = Modifier
                    .padding(top = 56.dp)
                    .background(androidx.compose.ui.graphics.Color.Transparent),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = SpeedMenuContainerAlpha)),
                border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = SpeedMenuBorderAlpha))
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    PlaybackSpeedOptions.forEach { option ->
                        TextButton(
                            onClick = {
                                onPlaybackSpeedChange(option)
                                expanded = false
                                menuVisible = false
                                onExpandedChange?.invoke()
                            },
                            modifier = Modifier.padding(vertical = SpeedMenuItemPaddingVertical.dp, horizontal = 12.dp)
                        ) {
                            Text(
                                text = formatPlaybackSpeed(option),
                                color = if (option == playbackSpeed) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenScrubber(
    currentPositionMs: Long,
    durationMs: Long,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0L) {
        Box(modifier = modifier)
        return
    }

    val progress = currentPositionMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()
    Column(modifier = modifier) {
        Slider(
            value = progress,
            onValueChange = { onValueChange((it * durationMs).toLong()) },
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = androidx.compose.ui.graphics.Color.White,
                activeTrackColor = androidx.compose.ui.graphics.Color.White,
                inactiveTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.24f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(currentPositionMs),
                color = androidx.compose.ui.graphics.Color.White
            )
            Text(
                text = formatDuration(durationMs),
                color = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

private fun mapBrightnessState(value: Float): BrightnessState {
    val gestureValue = value.coerceIn(0f, 1f)
    val screenBrightness = when {
        gestureValue <= 0f -> 0.01f
        gestureValue >= 1f -> 1f
        else -> gestureValue
    }
    val overlayAlpha = when {
        gestureValue >= ExtraDimThreshold -> 0f
        else -> ((ExtraDimThreshold - gestureValue) / ExtraDimThreshold * MaxExtraDimAlpha).coerceIn(0f, MaxExtraDimAlpha)
    }
    return BrightnessState(
        gestureValue = gestureValue,
        screenBrightness = screenBrightness,
        overlayAlpha = overlayAlpha
    )
}

private fun resolveInitialBrightness(activity: Activity?): Float {
    val brightness = activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    return if (brightness in 0f..1f) brightness else 1f
}

private fun formatPlaybackSpeed(speed: Float): String {
    return if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}x"
    } else {
        "${speed}x"
    }
}

private fun formatDuration(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun PlayerView.applySubtitleStyle() {
    subtitleView?.apply {
        setStyle(
            CaptionStyleCompat(
                AndroidColor.WHITE,
                AndroidColor.argb(SubtitleBackgroundAlpha, 0, 0, 0),
                AndroidColor.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                AndroidColor.TRANSPARENT,
                null
            )
        )
    }
}

@Composable
internal fun rememberIsPlayingState(player: ExoPlayer?): Boolean {
    var isPlaying by remember(player) { mutableStateOf(player?.isPlaying == true) }
    LaunchedEffect(player) {
        if (player == null) {
            isPlaying = false
            return@LaunchedEffect
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            player.removeListener(listener)
        }
    }
    return isPlaying
}

@Composable
private fun rememberPlaybackProgressState(player: ExoPlayer?): PlaybackProgressState {
    var currentPositionMs by remember(player) { mutableLongStateOf(player?.currentPosition ?: 0L) }
    var durationMs by remember(player) { mutableLongStateOf(player?.duration?.takeIf { it > 0 } ?: 0L) }
    LaunchedEffect(player) {
        if (player == null) {
            currentPositionMs = 0L
            durationMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            currentPositionMs = player.currentPosition
            durationMs = player.duration.takeIf { it > 0 } ?: durationMs
            delay(500L)
        }
    }
    return PlaybackProgressState(currentPositionMs, durationMs)
}
