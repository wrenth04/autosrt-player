package com.example.autosrtplayer.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.example.autosrtplayer.ui.favorites.FavoritesUiState

@UnstableApi
@Composable
fun PlayerScreen(
    sharedM3uUrl: String? = null,
    sharedSourceId: String? = null,
    onSharedM3uUrlConsumed: () -> Unit = {},
    onSharedSourceIdConsumed: () -> Unit = {},
    playbackViewModel: com.example.autosrtplayer.ui.playback.PlaybackViewModel = hiltViewModel(),
    playlistViewModel: com.example.autosrtplayer.ui.playlist.PlaylistViewModel = hiltViewModel(),
    favoritesViewModel: com.example.autosrtplayer.ui.favorites.FavoritesViewModel = hiltViewModel()
) {
    val playbackUiState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val favoritesUiState by favoritesViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(context) {
        playbackViewModel.initialize(context)
        playlistViewModel.initialize(context)
        favoritesViewModel.initialize(context)
    }

    fun loadFromId(id: String, sourcePrefix: String) {
        playbackViewModel.onSourceIdChange(id)
        playbackViewModel.openPlayer()
        playlistViewModel.handleEvent(PlaylistEvent.LoadFromId(id, sourcePrefix))
    }

    fun loadFromUrl(url: String) {
        playbackViewModel.openPlayer()
        playlistViewModel.handleEvent(PlaylistEvent.LoadFromUrl(url))
    }

    fun loadFromText(content: String) {
        playbackViewModel.openPlayer()
        playlistViewModel.handleEvent(PlaylistEvent.LoadFromText(content))
    }

    LaunchedEffect(sharedM3uUrl) {
        if (!sharedM3uUrl.isNullOrBlank()) {
            loadFromUrl(sharedM3uUrl)
            onSharedM3uUrlConsumed()
        }
    }

    LaunchedEffect(sharedSourceId) {
        if (!sharedSourceId.isNullOrBlank()) {
            loadFromId(sharedSourceId, playbackUiState.sourcePrefix)
            onSharedSourceIdConsumed()
        }
    }

    val activity = context as? Activity
    val currentSourceId = playlistUiState.currentSourceId
    LaunchedEffect(currentSourceId) {
        playbackViewModel.setCurrentSourceId(currentSourceId)
    }
    LaunchedEffect(playlistUiState.parsedEntry, playlistUiState.mediaItem) {
        playbackViewModel.setPlaybackContent(playlistUiState.parsedEntry, playlistUiState.mediaItem)
    }
    val isCurrentFavorite = currentSourceId != null && favoritesUiState.favoriteItems.any { item ->
        item.id.equals(currentSourceId, ignoreCase = true)
    }
    val toggleFavorite = { favoritesViewModel.toggleCurrentFavorite(currentSourceId) }
    val player = remember(context, playbackUiState.parsedEntry?.mediaUrl, playbackUiState.parsedEntry?.userAgent, playbackUiState.parsedEntry?.referrer) {
        playbackViewModel.getOrCreatePlayer(context)
    }
    val isPlaying = rememberIsPlayingState(player)
    val showingFavorites = playbackUiState.activePanel == PlayerPanel.Favorites
    val showingTodayHot = playbackUiState.activePanel == PlayerPanel.TodayHot
    val showingSettings = playbackUiState.activePanel == PlayerPanel.Settings
    val showingPlayerShell = playbackUiState.activePanel == PlayerPanel.Player

    DisposableEffect(activity, showingPlayerShell) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !showingPlayerShell)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (showingPlayerShell) {
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

    DisposableEffect(activity, playbackUiState.screenOrientationMode) {
        val originalOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = when (playbackUiState.screenOrientationMode) {
                ScreenOrientationMode.Auto -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                ScreenOrientationMode.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ScreenOrientationMode.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        onDispose {
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    BackHandler(enabled = showingFavorites || showingTodayHot || showingSettings) {
        playbackViewModel.openPlayer()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showingFavorites -> {
                FavoritesScreen(
                    items = favoritesUiState.favoriteItems,
                    onBack = { playbackViewModel.openPlayer() },
                    onItemClick = { id ->
                        loadFromId(id, playbackUiState.sourcePrefix)
                    },
                    onRemoveClick = { id ->
                        favoritesViewModel.removeFavorite(id)
                    },
                    onExportClick = { favoritesViewModel.exportFavorites(context) },
                    onImportConfirmed = { text -> favoritesViewModel.importFavorites(text) },
                    feedbackMessage = favoritesUiState.favoriteImportMessage ?: favoritesUiState.favoriteExportMessage,
                    onDismissFeedback = { favoritesViewModel.clearFavoriteMessages() }
                )
            }
            showingTodayHot -> {
                TodayHotScreen(
                    items = playbackUiState.todayHotItems,
                    isLoading = playbackUiState.isTodayHotLoading,
                    errorMessage = playbackUiState.todayHotErrorMessage,
                    onBack = { playbackViewModel.openPlayer() },
                    onItemClick = { code ->
                        loadFromId(code, playbackUiState.sourcePrefix)
                    }
                )
            }
            showingSettings -> {
                PlayerOptionsScreen(
                    playbackUiState = playbackUiState,
                    playlistUiState = playlistUiState,
                    favoritesUiState = favoritesUiState,
                    currentSourceId = currentSourceId,
                    isCurrentFavorite = isCurrentFavorite,
                    onSourceIdChange = { value ->
                        playbackViewModel.onSourceIdChange(value)
                    },
                    onLoadFromId = {
                        val sourceId = playbackUiState.sourceId
                        if (sourceId.isNotBlank()) {
                            loadFromId(sourceId, playbackUiState.sourcePrefix)
                        }
                    },
                    onTodayHotClick = { playbackViewModel.openTodayHot() },
                    onFavoritesClick = { playbackViewModel.openFavorites() },
                    onPlaylistUrlChange = { value ->
                        playlistViewModel.handleEvent(PlaylistEvent.UpdatePlaylistUrl(value))
                    },
                    onLoadFromUrl = {
                        val url = playlistUiState.playlistUrl
                        if (url.isNotBlank()) {
                            loadFromUrl(url)
                        }
                    },
                    onPlaylistTextChange = { value ->
                        playlistViewModel.handleEvent(PlaylistEvent.UpdatePlaylistText(value))
                    },
                    onLoadFromText = {
                        val content = playlistUiState.playlistText
                        if (content.isNotBlank()) {
                            loadFromText(content)
                        }
                    },
                    onSourcePrefixChange = { value ->
                        playbackViewModel.onSourcePrefixChange(value)
                    },
                    onSaveSourcePrefix = { playbackViewModel.saveSourcePrefix() },
                    onToggleFavorite = { toggleFavorite() },
                    onBack = { playbackViewModel.openPlayer() },
                    onStartupDestinationChange = { playbackViewModel.setStartupDestination(it) }
                )
            }
            else -> {
                FullscreenPlayer(
                    activity = activity,
                    player = player,
                    playbackSpeed = playbackUiState.playbackSpeed,
                    screenOrientationMode = playbackUiState.screenOrientationMode,
                    currentSourceId = currentSourceId,
                    currentRequestLabel = playlistUiState.currentRequestLabel,
                    isCurrentFavorite = isCurrentFavorite,
                    canToggleFavorite = !currentSourceId.isNullOrBlank(),
                    favoriteCount = favoritesUiState.favoriteItems.size,
                    isLoading = playlistUiState.isLoading,
                    loadingStage = playlistUiState.loadingStage,
                    errorMessage = playlistUiState.errorMessage,
                    onPlaybackSpeedChange = { speed ->
                        playbackViewModel.handleEvent(PlaybackEvent.SetPlaybackSpeed(speed))
                    },
                    onToggleScreenOrientationMode = { playbackViewModel.toggleScreenOrientationMode() },
                    onToggleFavorite = { toggleFavorite() },
                    onOpenTodayHot = { playbackViewModel.openTodayHot() },
                    onOpenFavorites = { playbackViewModel.openFavorites() },
                    onOpenSettings = { playbackViewModel.openSettings() },
                    onSubmitSourceId = { id ->
                        loadFromId(id, playbackUiState.sourcePrefix)
                    }
                )
            }
        }

        playlistUiState.sourceResolveRequest?.let { request ->
            SourceResolveWebViewHost(
                request = request,
                onHtmlResolved = { requestId, html, userAgent, finalUrl ->
                    playlistViewModel.handleEvent(PlaylistEvent.OnSourceHtmlResolved(requestId, html, userAgent, finalUrl))
                },
                onResolveFailed = { requestId, message ->
                    playlistViewModel.handleEvent(PlaylistEvent.OnSourceResolveFailed(requestId, message))
                }
            )
        }
    }
}

@Composable
private fun PlayerOptionsScreen(
    playbackUiState: PlaybackUiState,
    playlistUiState: PlaylistUiState,
    favoritesUiState: FavoritesUiState,
    currentSourceId: String?,
    isCurrentFavorite: Boolean,
    onSourceIdChange: (String) -> Unit,
    onLoadFromId: () -> Unit,
    onTodayHotClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onPlaylistUrlChange: (String) -> Unit,
    onLoadFromUrl: () -> Unit,
    onPlaylistTextChange: (String) -> Unit,
    onLoadFromText: () -> Unit,
    onSourcePrefixChange: (String) -> Unit,
    onSaveSourcePrefix: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onStartupDestinationChange: (StartupDestination) -> Unit
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var techInfoExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回播放器"
                )
            }
            Text("設定 / 選項", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.width(48.dp))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("開 APP 時顯示")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StartupDestinationButton(
                        selected = playbackUiState.startupDestination == StartupDestination.Player,
                        text = "全畫面 Player",
                        onClick = { onStartupDestinationChange(StartupDestination.Player) }
                    )
                    StartupDestinationButton(
                        selected = playbackUiState.startupDestination == StartupDestination.TodayHot,
                        text = "每日熱門",
                        onClick = { onStartupDestinationChange(StartupDestination.TodayHot) }
                    )
                    StartupDestinationButton(
                        selected = playbackUiState.startupDestination == StartupDestination.Favorites,
                        text = "我的最愛",
                        onClick = { onStartupDestinationChange(StartupDestination.Favorites) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = playbackUiState.sourceId,
                onValueChange = onSourceIdChange,
                modifier = Modifier.weight(1f),
                label = { Text("輸入影片 ID") },
                placeholder = { Text("例如：ABCD-123") },
                minLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onLoadFromId() })
            )

            Button(
                onClick = onLoadFromId,
                modifier = Modifier.height(48.dp)
            ) {
                Text("播放")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTodayHotClick,
                modifier = Modifier.weight(1f),
                enabled = !playlistUiState.isLoading
            ) {
                Text(if (playlistUiState.isLoading) "載入中…" else "熱門")
            }

            Button(
                onClick = onFavoritesClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("最愛 (${favoritesUiState.favoriteItems.size})")
            }
        }

        if (playlistUiState.isLoading) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator()
                    val stageLabel = when (playlistUiState.loadingStage) {
                        LoadingStage.ResolvingId -> "正在解析 ID…"
                        LoadingStage.FetchingPlaylist -> "正在取得播放清單…"
                        LoadingStage.ResolvingSource -> "解析中…"
                        LoadingStage.BuildingPlayer -> "播放器初始化中…"
                        LoadingStage.Idle -> "載入中…"
                    }
                    Text(stageLabel)
                    playlistUiState.currentRequestLabel?.let { Text("來源：$it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        playlistUiState.errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        playlistUiState.parsedEntry?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("標題：${it.title ?: "(未提供)"}")
                    Text("字幕狀態：${if (it.subtitleUrl.isNullOrBlank()) "未載入" else "已載入"}")
                    Text("來源狀態：可播放")
                    Button(
                        onClick = onToggleFavorite,
                        enabled = !currentSourceId.isNullOrBlank()
                    ) {
                        Text(
                            when {
                                currentSourceId.isNullOrBlank() -> "無可儲存 ID"
                                isCurrentFavorite -> "已加入稍後觀看"
                                else -> "加入稍後觀看"
                            }
                        )
                    }
                    TextButton(onClick = { techInfoExpanded = !techInfoExpanded }) {
                        Text(if (techInfoExpanded) "隱藏技術資訊" else "查看技術資訊")
                    }
                    if (techInfoExpanded) {
                        Text("Media URL: ${it.mediaUrl}")
                        Text("User-Agent: ${it.userAgent ?: "(none)"}")
                        Text("Referer: ${it.referrer ?: "(none)"}")
                        Text("Subtitle: ${it.subtitleUrl ?: "(none)"}")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Text(if (advancedExpanded) "收合進階選項" else "展開進階選項")
                }
                if (advancedExpanded) {
                    OutlinedTextField(
                        value = playlistUiState.playlistUrl,
                        onValueChange = onPlaylistUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("M3U8 網址") },
                        placeholder = { Text("貼上完整播放清單網址") },
                        minLines = 1,
                        singleLine = true
                    )
                    Button(
                        onClick = onLoadFromUrl,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("用網址播放")
                    }
                    OutlinedTextField(
                        value = playlistUiState.playlistText,
                        onValueChange = onPlaylistTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("貼上 M3U 內容") },
                        placeholder = { Text("貼上 #EXTM3U 開頭的文字內容") },
                        minLines = 6
                    )
                    Button(
                        onClick = onLoadFromText,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("匯入 M3U")
                    }
                    OutlinedTextField(
                        value = playbackUiState.sourcePrefix,
                        onValueChange = onSourcePrefixChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("來源設定") },
                        placeholder = { Text("例如：https://github.com/.../srt/") },
                        minLines = 1
                    )
                    Button(
                        onClick = onSaveSourcePrefix,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("儲存設定")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun StartupDestinationButton(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    ) {
        Text(if (selected) "✓ $text" else text)
    }
}
