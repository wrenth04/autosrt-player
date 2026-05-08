package com.example.autosrtplayer.ui

import android.app.Activity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
fun PlayerScreen(
    sharedM3uUrl: String? = null,
    sharedSourceId: String? = null,
    onSharedM3uUrlConsumed: () -> Unit = {},
    onSharedSourceIdConsumed: () -> Unit = {},
    viewModel: PlayerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(context) {
        viewModel.initialize(context)
    }
    LaunchedEffect(sharedM3uUrl) {
        if (!sharedM3uUrl.isNullOrBlank()) {
            viewModel.loadFromSharedUrl(sharedM3uUrl)
            onSharedM3uUrlConsumed()
        }
    }
    LaunchedEffect(sharedSourceId) {
        if (!sharedSourceId.isNullOrBlank()) {
            viewModel.loadFromExternalId(sharedSourceId)
            onSharedSourceIdConsumed()
        }
    }

    val activity = context as? Activity
    val entry = uiState.parsedEntry
    val currentSourceId = uiState.currentSourceId
    val isCurrentFavorite = currentSourceId != null && uiState.favoriteItems.any { item ->
        item.id.equals(currentSourceId, ignoreCase = true)
    }
    val player = remember(context, entry?.mediaUrl, entry?.userAgent, entry?.referrer) {
        viewModel.getOrCreatePlayer(context)
    }
    val isPlaying = rememberIsPlayingState(player)
    val showingFavorites = uiState.isFavoritesVisible
    val showingTodayHot = uiState.isTodayHotVisible
    val showingSettings = uiState.isSettingsVisible
    val showingPlayerShell = !showingFavorites && !showingTodayHot && !showingSettings

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

    DisposableEffect(activity, uiState.screenOrientationMode) {
        val originalOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = when (uiState.screenOrientationMode) {
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

    BackHandler(enabled = showingFavorites) {
        viewModel.closeFavorites()
    }
    BackHandler(enabled = showingTodayHot) {
        viewModel.closeTodayHot()
    }
    BackHandler(enabled = showingSettings) {
        viewModel.closeSettings()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showingFavorites -> {
                FavoritesScreen(
                    items = uiState.favoriteItems,
                    onBack = viewModel::closeFavorites,
                    onItemClick = viewModel::playFavorite,
                    onRemoveClick = viewModel::removeFavorite
                )
            }
            showingTodayHot -> {
                TodayHotScreen(
                    items = uiState.todayHotItems,
                    isLoading = uiState.isTodayHotLoading,
                    errorMessage = uiState.todayHotErrorMessage,
                    onBack = viewModel::closeTodayHot,
                    onItemClick = viewModel::playTodayHotCode
                )
            }
            showingSettings -> {
                PlayerOptionsScreen(
                    uiState = uiState,
                    currentSourceId = currentSourceId,
                    isCurrentFavorite = isCurrentFavorite,
                    onSourceIdChange = viewModel::onSourceIdChange,
                    onLoadFromId = viewModel::loadFromId,
                    onTodayHotClick = viewModel::loadTodayHot,
                    onFavoritesClick = viewModel::openFavorites,
                    onPlaylistUrlChange = viewModel::onPlaylistUrlChange,
                    onLoadFromUrl = { viewModel.loadFromUrl() },
                    onPlaylistTextChange = viewModel::onPlaylistTextChange,
                    onLoadFromText = viewModel::loadFromText,
                    onSourcePrefixChange = viewModel::onSourcePrefixChange,
                    onSaveSourcePrefix = viewModel::saveSourcePrefix,
                    onToggleFavorite = viewModel::toggleCurrentFavorite,
                    onBack = viewModel::closeSettings,
                    onStartupDestinationChange = viewModel::setStartupDestination
                )
            }
            else -> {
                FullscreenPlayer(
                    activity = activity,
                    player = player,
                    playbackSpeed = uiState.playbackSpeed,
                    screenOrientationMode = uiState.screenOrientationMode,
                    currentSourceId = currentSourceId,
                    currentRequestLabel = uiState.currentRequestLabel,
                    isCurrentFavorite = isCurrentFavorite,
                    canToggleFavorite = !currentSourceId.isNullOrBlank(),
                    favoriteCount = uiState.favoriteItems.size,
                    isLoading = uiState.isLoading,
                    loadingStage = uiState.loadingStage,
                    errorMessage = uiState.errorMessage,
                    thumbnailState = uiState.thumbnailState,
                    onLoadPausedThumbnails = viewModel::loadPausedThumbnailsIfNeeded,
                    onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
                    onToggleScreenOrientationMode = viewModel::toggleScreenOrientationMode,
                    onToggleFavorite = viewModel::toggleCurrentFavorite,
                    onOpenTodayHot = viewModel::loadTodayHot,
                    onOpenFavorites = viewModel::openFavorites,
                    onOpenSettings = viewModel::openSettings,
                    onSubmitSourceId = viewModel::loadFromExternalId
                )
            }
        }

        uiState.sourceResolveRequest?.let { request ->
            SourceResolveWebViewHost(
                request = request,
                onHtmlResolved = viewModel::onSourceHtmlResolved,
                onResolveFailed = viewModel::onSourceResolveFailed
            )
        }
    }
}

@Composable
private fun PlayerOptionsScreen(
    uiState: PlayerUiState,
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
                        selected = uiState.startupDestination == StartupDestination.Player,
                        text = "全畫面 Player",
                        onClick = { onStartupDestinationChange(StartupDestination.Player) }
                    )
                    StartupDestinationButton(
                        selected = uiState.startupDestination == StartupDestination.TodayHot,
                        text = "每日熱門",
                        onClick = { onStartupDestinationChange(StartupDestination.TodayHot) }
                    )
                    StartupDestinationButton(
                        selected = uiState.startupDestination == StartupDestination.Favorites,
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
                value = uiState.sourceId,
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
                enabled = !uiState.isTodayHotLoading
            ) {
                Text(if (uiState.isTodayHotLoading) "載入中…" else "熱門")
            }

            Button(
                onClick = onFavoritesClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("最愛 (${uiState.favoriteItems.size})")
            }
        }

        if (uiState.isLoading) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator()
                    val stageLabel = when (uiState.loadingStage) {
                        LoadingStage.ResolvingId -> "正在解析 ID…"
                        LoadingStage.FetchingPlaylist -> "正在取得播放清單…"
                        LoadingStage.ResolvingSource -> "解析中…"
                        LoadingStage.BuildingPlayer -> "播放器初始化中…"
                        LoadingStage.Idle -> "載入中…"
                    }
                    Text(stageLabel)
                    uiState.currentRequestLabel?.let { Text("來源：$it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        uiState.errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (uiState.errorType == UiErrorType.PrefixMissing) {
                        TextButton(onClick = { advancedExpanded = true }) {
                            Text("前往進階選項設定來源")
                        }
                    }
                }
            }
        }

        uiState.parsedEntry?.let {
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
                        value = uiState.playlistUrl,
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
                        value = uiState.playlistText,
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
                        value = uiState.sourcePrefix,
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
