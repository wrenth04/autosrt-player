package com.example.autosrtplayer.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.example.autosrtplayer.data.playback.PlayerFactory

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
    player: androidx.media3.exoplayer.ExoPlayer?,
    onToggleFullscreen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
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
