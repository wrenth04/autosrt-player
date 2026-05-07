package com.example.autosrtplayer.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
            isCurrentFavorite = isCurrentFavorite,
            canToggleFavorite = !currentSourceId.isNullOrBlank(),
            onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
            onToggleFavorite = viewModel::toggleCurrentFavorite,
            onToggleFullscreen = viewModel::toggleFullscreen
        )
        return
    }
    if (uiState.isFavoritesVisible) {
        BackHandler(enabled = true) {
            viewModel.closeFavorites()
        }
        FavoritesScreen(
            items = uiState.favoriteItems,
            onBack = viewModel::closeFavorites,
            onItemClick = viewModel::playFavorite,
            onRemoveClick = viewModel::removeFavorite
        )
        return
    }
    if (uiState.isTodayHotVisible) {
        BackHandler(enabled = true) {
            viewModel.closeTodayHot()
        }
        TodayHotScreen(
            items = uiState.todayHotItems,
            isLoading = uiState.isTodayHotLoading,
            errorMessage = uiState.todayHotErrorMessage,
            onBack = viewModel::closeTodayHot,
            onItemClick = viewModel::playTodayHotCode
        )
        return
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var techInfoExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AutoSRT Player", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "輸入影片 ID 後，直接載入並播放",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = uiState.sourceId,
            onValueChange = viewModel::onSourceIdChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("輸入影片 ID") },
            placeholder = { Text("例如：ABCD-123") },
            minLines = 1,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { viewModel.loadFromId() })
        )

        Button(
            onClick = viewModel::loadFromId,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("載入並播放")
        }

        Button(
            onClick = viewModel::loadTodayHot,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isTodayHotLoading
        ) {
            Text(if (uiState.isTodayHotLoading) "載入今日熱門…" else "今日熱門")
        }

        Button(
            onClick = viewModel::openFavorites,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("我的最愛 (${uiState.favoriteItems.size})")
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

        uiState.sourceResolveRequest?.let { request ->
            SourceResolveWebViewHost(
                request = request,
                onHtmlResolved = viewModel::onSourceHtmlResolved,
                onResolveFailed = viewModel::onSourceResolveFailed
            )
        }

        InlinePlayer(
            player = player,
            playbackSpeed = uiState.playbackSpeed,
            onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
            onToggleFullscreen = viewModel::toggleFullscreen
        )

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

        entry?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("標題：${it.title ?: "(未提供)"}")
                    Text("字幕狀態：${if (it.subtitleUrl.isNullOrBlank()) "未載入" else "已載入"}")
                    Text("來源狀態：可播放")
                    Button(
                        onClick = viewModel::toggleCurrentFavorite,
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
                        onValueChange = viewModel::onPlaylistUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("M3U8 網址") },
                        placeholder = { Text("貼上完整播放清單網址") },
                        minLines = 1,
                        singleLine = true
                    )
                    Button(
                        onClick = { viewModel.loadFromUrl() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("用網址播放")
                    }
                    OutlinedTextField(
                        value = uiState.playlistText,
                        onValueChange = viewModel::onPlaylistTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("貼上 M3U 內容") },
                        placeholder = { Text("貼上 #EXTM3U 開頭的文字內容") },
                        minLines = 6
                    )
                    Button(
                        onClick = viewModel::loadFromText,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("匯入 M3U")
                    }
                    OutlinedTextField(
                        value = uiState.sourcePrefix,
                        onValueChange = viewModel::onSourcePrefixChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("來源設定") },
                        placeholder = { Text("例如：https://github.com/.../srt/") },
                        minLines = 1
                    )
                    Button(
                        onClick = viewModel::saveSourcePrefix,
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
