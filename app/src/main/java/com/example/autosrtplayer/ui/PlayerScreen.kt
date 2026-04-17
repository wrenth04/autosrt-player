package com.example.autosrtplayer.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
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
private val PlaybackSpeedOptions = listOf(0.5f, 1f, 2f, 4f, 8f)

private enum class OverlayGestureMode {
    Seek,
    Brightness,
    Volume
}

private data class GestureHudState(
    val icon: ImageVector,
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

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val entry = uiState.parsedEntry
    val player = remember(context, entry?.mediaUrl, entry?.userAgent, entry?.referrer) {
        viewModel.getOrCreatePlayer(context)
    }
    val isPlaying = rememberIsPlayingState(player)

    DisposableEffect(activity, uiState.isFullscreen) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !uiState.isFullscreen)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (uiState.isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(activity, isPlaying) {
        val window = activity?.window
        if (window != null) {
            if (isPlaying) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler(enabled = uiState.isFullscreen) {
        viewModel.setFullscreen(false)
    }

    if (uiState.isFullscreen) {
        FullscreenPlayer(
            activity = activity,
            player = player,
            playbackSpeed = uiState.playbackSpeed,
            onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
            onToggleFullscreen = viewModel::toggleFullscreen
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AutoSRT Player", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = uiState.playlistUrl,
            onValueChange = viewModel::onPlaylistUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Playlist URL") },
            minLines = 1
        )

        Button(
            onClick = viewModel::loadFromUrl,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("由網址載入")
        }

        OutlinedTextField(
            value = uiState.playlistText,
            onValueChange = viewModel::onPlaylistTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("EXTM3U 內容") },
            minLines = 8
        )

        Button(
            onClick = viewModel::loadFromText,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("由文字播放")
        }

        if (uiState.isLoading) {
            CircularProgressIndicator()
        }

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        entry?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Title: ${it.title ?: "(none)"}")
                    Text("Media URL: ${it.mediaUrl}")
                    Text("User-Agent: ${it.userAgent ?: "(none)"}")
                    Text("Referer: ${it.referrer ?: "(none)"}")
                    Text("Subtitle: ${it.subtitleUrl ?: "(none)"}")
                }
            }
        }

        InlinePlayer(
            player = player,
            playbackSpeed = uiState.playbackSpeed,
            onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
            onToggleFullscreen = viewModel::toggleFullscreen
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun InlinePlayer(
    player: androidx.media3.exoplayer.ExoPlayer?,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    onToggleFullscreen: () -> Unit
) {
    if (player == null) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color.Black)
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
                .background(Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
                .size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = "Enter fullscreen",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun FullscreenPlayer(
    activity: Activity?,
    player: androidx.media3.exoplayer.ExoPlayer?,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    onToggleFullscreen: () -> Unit
) {
    var hudState by remember { mutableStateOf<GestureHudState?>(null) }
    var appBrightness by remember(activity) {
        mutableFloatStateOf(resolveInitialBrightness(activity))
    }
    var dimOverlayAlpha by remember(activity) {
        mutableFloatStateOf(mapBrightnessState(appBrightness).overlayAlpha)
    }
    val progressState = rememberPlaybackProgressState(player)
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var controlsInteractionTick by remember(player) { mutableLongStateOf(0L) }
    var isScrubbing by remember(player) { mutableStateOf(false) }
    var scrubPositionMs by remember(player) { mutableLongStateOf(0L) }
    val displayedPositionMs = if (isScrubbing) scrubPositionMs else progressState.currentPositionMs
    val latestPlayer by rememberUpdatedState(player)
    val density = LocalDensity.current

    fun pingControls() {
        controlsInteractionTick += 1
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val initialBrightnessState = mapBrightnessState(appBrightness)
        dimOverlayAlpha = initialBrightnessState.overlayAlpha
        window?.let {
            val attributes = it.attributes
            attributes.screenBrightness = initialBrightnessState.screenBrightness
            it.attributes = attributes
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
            .background(Color.Black)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.takeIf { it > 0f } ?: 1f
        val heightPx = with(density) { maxHeight.toPx() }.takeIf { it > 0f } ?: 1f

        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = false
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

        if (dimOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimOverlayAlpha))
            )
        }

        if (!controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
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
                    .background(Color.Black.copy(alpha = ControlOverlayAlpha), shape = CircleShape)
                    .size(CenterButtonSize.dp)
            ) {
                val isPlaying = player?.isPlaying == true
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        hudState?.let {
            GestureHud(
                state = it,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (controlsVisible) {
            PlaybackSpeedButton(
                playbackSpeed = playbackSpeed,
                onPlaybackSpeedChange = {
                    onPlaybackSpeedChange(it)
                    pingControls()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                overlayAlpha = ControlOverlayAlpha,
                onExpandedChange = { pingControls() }
            )

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
            )

            IconButton(
                onClick = {
                    pingControls()
                    onToggleFullscreen()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = ControlOverlayAlpha), shape = MaterialTheme.shapes.small)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun GestureZone(
    modifier: Modifier,
    mode: OverlayGestureMode,
    widthPx: Float,
    heightPx: Float,
    player: androidx.media3.exoplayer.ExoPlayer?,
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
                        startBrightness = resolveInitialBrightness(activity)
                    }
                    if (mode == OverlayGestureMode.Volume) {
                        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                        startVolume = currentVolume to maxVolume
                    }
                    if (mode == OverlayGestureMode.Seek) {
                        startPositionMs = player?.currentPosition ?: 0L
                    }
                },
                onDragEnd = {
                    totalDragX = 0f
                    totalDragY = 0f
                },
                onDragCancel = {
                    totalDragX = 0f
                    totalDragY = 0f
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    when (mode) {
                        OverlayGestureMode.Brightness -> {
                            val delta = -(totalDragY / heightPx)
                            onBrightnessChange((startBrightness + delta).coerceIn(0f, 1f))
                        }

                        OverlayGestureMode.Volume -> {
                            val (currentVolume, maxVolume) = startVolume
                            if (maxVolume > 0) {
                                val stepDelta = (-(totalDragY / heightPx) * maxVolume).roundToInt()
                                val targetVolume = (currentVolume + stepDelta).coerceIn(0, maxVolume)
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                                onVolumeChange(targetVolume, maxVolume)
                            }
                        }

                        OverlayGestureMode.Seek -> {
                            val duration = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                            val mappedDelta = ((totalDragX / widthPx) * SeekMaxOffsetMs).roundToInt().toLong()
                            val unclampedTarget = startPositionMs + mappedDelta
                            val targetPosition = if (duration > 0) {
                                unclampedTarget.coerceIn(0L, duration)
                            } else {
                                maxOf(0L, unclampedTarget)
                            }
                            player?.seekTo(targetPosition)
                            onSeekChange(targetPosition - startPositionMs, targetPosition)
                        }
                    }
                }
            )
        }
    )
}

@Composable
private fun FullscreenScrubber(
    currentPositionMs: Long,
    durationMs: Long,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSeekable = durationMs > 0L
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = ScrubberOverlayAlpha))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Slider(
                value = if (isSeekable) currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()) else 0f,
                onValueChange = { onValueChange(it.roundToInt().toLong()) },
                valueRange = 0f..maxOf(durationMs.toFloat(), 1f),
                onValueChangeFinished = onValueChangeFinished,
                enabled = isSeekable,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(currentPositionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (isSeekable) formatDuration(durationMs) else "--:--",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
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
    overlayAlpha: Float = 0.45f,
    onExpandedChange: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Button(
            onClick = {
                expanded = !expanded
                onExpandedChange?.invoke()
            },
            modifier = Modifier.height(40.dp),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black.copy(alpha = overlayAlpha),
                contentColor = Color.White
            )
        ) {
            Text(formatPlaybackSpeed(playbackSpeed))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            PlaybackSpeedOptions.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(formatPlaybackSpeed(speed)) },
                    onClick = {
                        expanded = false
                        onPlaybackSpeedChange(speed)
                    }
                )
            }
        }
    }
}

@Composable
private fun GestureHud(
    state: GestureHudState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.width(176.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = GestureHudAlpha))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = state.icon,
                contentDescription = state.label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = state.label,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.valueText?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.progress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.28f)
                )
            }
        }
    }
}

@Composable
private fun rememberIsPlayingState(
    player: androidx.media3.exoplayer.ExoPlayer?
): Boolean {
    var isPlaying by remember(player) { mutableStateOf(player?.isPlaying == true) }

    DisposableEffect(player) {
        if (player == null) {
            isPlaying = false
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    isPlaying = player.isPlaying
                }
            }
            player.addListener(listener)
            isPlaying = player.isPlaying
            onDispose {
                player.removeListener(listener)
            }
        }
    }

    return isPlaying
}

@Composable
private fun rememberPlaybackProgressState(
    player: androidx.media3.exoplayer.ExoPlayer?
): PlaybackProgressState {
    var currentPositionMs by remember(player) { mutableLongStateOf(player?.currentPosition ?: 0L) }
    var durationMs by remember(player) {
        mutableLongStateOf(player?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L)
    }

    DisposableEffect(player) {
        if (player == null) {
            currentPositionMs = 0L
            durationMs = 0L
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    currentPositionMs = player.currentPosition
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                }
            }
            player.addListener(listener)
            currentPositionMs = player.currentPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            onDispose {
                player.removeListener(listener)
            }
        }
    }

    LaunchedEffect(player) {
        while (player != null) {
            currentPositionMs = player.currentPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            delay(250)
        }
    }

    return PlaybackProgressState(
        currentPositionMs = currentPositionMs,
        durationMs = durationMs
    )
}

private fun PlayerView.applySubtitleStyle() {
    subtitleView?.setStyle(
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

private fun mapBrightnessState(gestureValue: Float): BrightnessState {
    val clampedGestureValue = gestureValue.coerceIn(0f, 1f)
    return if (clampedGestureValue >= ExtraDimThreshold) {
        val normalizedBrightness = (clampedGestureValue - ExtraDimThreshold) / (1f - ExtraDimThreshold)
        BrightnessState(
            gestureValue = clampedGestureValue,
            screenBrightness = (MinBrightness + normalizedBrightness * (1f - MinBrightness)).coerceIn(MinBrightness, 1f),
            overlayAlpha = 0f
        )
    } else {
        val overlayProgress = 1f - (clampedGestureValue / ExtraDimThreshold)
        BrightnessState(
            gestureValue = clampedGestureValue,
            screenBrightness = MinBrightness,
            overlayAlpha = (overlayProgress * MaxExtraDimAlpha).coerceIn(0f, MaxExtraDimAlpha)
        )
    }
}

private fun resolveInitialBrightness(activity: Activity?): Float {
    val value = activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    if (value !in MinBrightness..1f) return 0.5f
    val normalizedBrightness = (value - MinBrightness) / (1f - MinBrightness)
    return (ExtraDimThreshold + normalizedBrightness * (1f - ExtraDimThreshold)).coerceIn(ExtraDimThreshold, 1f)
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatPlaybackSpeed(speed: Float): String {
    val normalized = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "${normalized}x"
}
