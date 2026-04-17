package com.example.autosrtplayer.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.media3.ui.PlayerView
import com.example.autosrtplayer.data.playback.PlayerFactory
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GestureHudTimeoutMs = 900L
private const val MinBrightness = 0.05f
private const val SeekMaxOffsetMs = 180_000L
private const val CenterButtonSize = 72

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

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val playerFactory = remember { PlayerFactory() }
    val entry = uiState.parsedEntry

    val player = remember(entry?.mediaUrl, entry?.userAgent, entry?.referrer) {
        entry?.let {
            playerFactory.create(
                context = context,
                userAgent = it.userAgent,
                referrer = it.referrer
            ).apply {
                uiState.mediaItem?.let(::setMediaItem)
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

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

    BackHandler(enabled = uiState.isFullscreen) {
        viewModel.setFullscreen(false)
    }

    if (uiState.isFullscreen) {
        FullscreenPlayer(
            activity = activity,
            player = player,
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
            onToggleFullscreen = viewModel::toggleFullscreen
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun InlinePlayer(
    player: androidx.media3.exoplayer.ExoPlayer?,
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
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.player = player }
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
    onToggleFullscreen: () -> Unit
) {
    var hudState by remember { mutableStateOf<GestureHudState?>(null) }
    var appBrightness by remember(activity) {
        mutableFloatStateOf(resolveInitialBrightness(activity))
    }
    val latestPlayer by rememberUpdatedState(player)
    val density = LocalDensity.current

    LaunchedEffect(hudState) {
        if (hudState != null) {
            delay(GestureHudTimeoutMs)
            hudState = null
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
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = player }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
        ) {
            GestureZone(
                modifier = Modifier
                    .weight(0.24f)
                    .fillMaxHeight(),
                mode = OverlayGestureMode.Brightness,
                widthPx = widthPx,
                heightPx = heightPx,
                player = latestPlayer,
                onBrightnessChange = { value ->
                    appBrightness = value
                    activity?.window?.let { window ->
                        val attributes = window.attributes
                        attributes.screenBrightness = value
                        window.attributes = attributes
                    }
                    hudState = GestureHudState(
                        icon = Icons.Filled.Brightness6,
                        label = "亮度",
                        valueText = "${(value * 100).roundToInt()}%",
                        progress = value
                    )
                },
                onVolumeChange = { _, _ -> },
                onSeekChange = { _, _ -> }
            )

            GestureZone(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight(),
                mode = OverlayGestureMode.Seek,
                widthPx = widthPx,
                heightPx = heightPx,
                player = latestPlayer,
                onBrightnessChange = { },
                onVolumeChange = { _, _ -> },
                onSeekChange = { deltaMs, targetMs ->
                    hudState = GestureHudState(
                        icon = Icons.Filled.FastForward,
                        label = if (deltaMs >= 0) "快轉" else "倒退",
                        valueText = buildString {
                            append(if (deltaMs >= 0) "+" else "-")
                            append(formatDuration(abs(deltaMs)))
                            append(" · ")
                            append(formatDuration(targetMs))
                        }
                    )
                }
            )

            GestureZone(
                modifier = Modifier
                    .weight(0.24f)
                    .fillMaxHeight(),
                mode = OverlayGestureMode.Volume,
                widthPx = widthPx,
                heightPx = heightPx,
                player = latestPlayer,
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
                onSeekChange = { _, _ -> }
            )
        }

        IconButton(
            onClick = {
                player?.let {
                    val wasPlaying = it.isPlaying
                    if (wasPlaying) it.pause() else it.play()
                    hudState = GestureHudState(
                        icon = if (wasPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        label = if (wasPlaying) "暫停" else "播放"
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape)
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

        hudState?.let {
            GestureHud(
                state = it,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small)
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

@Composable
private fun GestureZone(
    modifier: Modifier,
    mode: OverlayGestureMode,
    widthPx: Float,
    heightPx: Float,
    player: androidx.media3.exoplayer.ExoPlayer?,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Int, Int) -> Unit,
    onSeekChange: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var startBrightness by remember { mutableFloatStateOf(MinBrightness) }
    var startVolume by remember { mutableStateOf(0 to 0) }
    var startPositionMs by remember { mutableStateOf(0L) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier.pointerInput(mode, player, widthPx, heightPx) {
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
                            onBrightnessChange((startBrightness + delta).coerceIn(MinBrightness, 1f))
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
private fun GestureHud(
    state: GestureHudState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.width(220.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = state.icon,
                contentDescription = state.label,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = state.label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.valueText?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
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

private fun resolveInitialBrightness(activity: Activity?): Float {
    val value = activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    return if (value in MinBrightness..1f) value else 0.5f
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
