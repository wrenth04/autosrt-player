package com.example.autosrtplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
        val originalCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode
        } else {
            null
        }

        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !showingPlayerShell)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (showingPlayerShell) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                // Allow content to extend into display cutout area in landscape
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val attributes = window.attributes
                    attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attributes
                }

                // Request layout to apply new window policy
                window.decorView.requestLayout()
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())

                // Restore original cutout mode when leaving player shell
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && originalCutoutMode != null) {
                    val attributes = window.attributes
                    attributes.layoutInDisplayCutoutMode = originalCutoutMode
                    window.attributes = attributes
                }
            }
        }

        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())

                // Restore original cutout mode on dispose
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && originalCutoutMode != null) {
                    val attributes = window.attributes
                    attributes.layoutInDisplayCutoutMode = originalCutoutMode
                    window.attributes = attributes
                }
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
                    onRemoveClick = viewModel::removeFavorite,
                    onExportClick = viewModel::exportFavorites,
                    onImportConfirmed = viewModel::importFavorites,
                    feedbackMessage = uiState.favoriteImportMessage ?: uiState.favoriteExportMessage,
                    onDismissFeedback = viewModel::clearFavoriteMessages
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
                    onPatTokenChange = viewModel::onPatTokenChange,
                    onPatTokenEnabledChange = viewModel::onPatTokenEnabledChange,
                    onSourcePrefixChange = viewModel::onSourcePrefixChange,
                    onSaveSourcePrefix = viewModel::saveSourcePrefix,
                    onToggleFavorite = viewModel::toggleCurrentFavorite,
                    onBack = viewModel::closeSettings,
                    onStartupDestinationChange = viewModel::setStartupDestination,
                    onVrContentModeChange = viewModel::setVrContentMode,
                    onVrFieldOfViewChange = viewModel::setVrFieldOfView,
                    onVrSourceLayoutChange = viewModel::setVrSourceLayout,
                    onVrProjectionChange = viewModel::setVrProjection,
                    onVrDisplayOutputChange = viewModel::setVrDisplayOutput,
                    onVrStereoAspectModeChange = viewModel::setVrStereoAspectMode,
                    onVrSourceOrientationChange = viewModel::setVrSourceOrientation,
                    onVrForwardDirectionChange = viewModel::setVrForwardDirection,
                    onVrHeadTrackingEnabledChange = viewModel::setVrHeadTrackingEnabled,
                    onVrCustomHorizontalFovChange = viewModel::setVrCustomHorizontalFovDegrees,
                    onVrStereoParallaxPercentChange = viewModel::setVrStereoParallaxPercent,
                    onVrFlatScreenSizePercentChange = viewModel::setVrFlatScreenSizePercent,
                    onVrDepthStereoEnabledChange = viewModel::setVrDepthStereoEnabled,
                    onSelectDepthModel = viewModel::selectDepthModel,
                    onDownloadDepthModel = viewModel::downloadDepthModel,
                    onDeleteDepthModel = viewModel::deleteDepthModelById,
                    onGetTotalModelSizeMB = viewModel::getTotalModelSizeMB,
                    onApplyPseudoVrSbsPreset = viewModel::applyPseudoVrSbsPreset
                )
            }
            else -> {
                FullscreenPlayer(
                    activity = activity,
                    player = player,
                    playbackSpeed = uiState.playbackSpeed,
                    screenOrientationMode = uiState.screenOrientationMode,
                    vrConfig = uiState.vrConfig,
                    vrViewAngles = uiState.vrViewAngles,
                    isVrHeadTrackingEnabled = uiState.isVrHeadTrackingEnabled,
                    selectedDepthModel = viewModel.getSelectedDepthModel(),
                    depthModelFile = viewModel.getSelectedDepthModelFile(),
                    currentSourceId = currentSourceId,
                    currentRequestLabel = uiState.currentRequestLabel,
                    isCurrentFavorite = isCurrentFavorite,
                    canToggleFavorite = !currentSourceId.isNullOrBlank(),
                    favoriteCount = uiState.favoriteItems.size,
                    isLoading = uiState.isLoading,
                    loadingStage = uiState.loadingStage,
                    errorMessage = uiState.errorMessage,
                    onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
                    onToggleScreenOrientationMode = viewModel::toggleScreenOrientationMode,
                    onVrViewDrag = viewModel::updateVrViewAngles,
                    onVrFlatScreenSizeChange = viewModel::setVrFlatScreenSizePercentTransient,
                    onVrFlatScreenSizeChangeFinished = viewModel::setVrFlatScreenSizePercent,
                    onVrCameraFovChange = viewModel::setVrCameraFovDegreesTransient,
                    onVrCameraFovChangeFinished = viewModel::setVrCameraFovDegrees,
                    onResetVrView = viewModel::resetVrViewAngles,
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
    onPatTokenChange: (String) -> Unit,
    onPatTokenEnabledChange: (Boolean) -> Unit,
    onSourcePrefixChange: (String) -> Unit,
    onSaveSourcePrefix: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onStartupDestinationChange: (StartupDestination) -> Unit,
    onVrContentModeChange: (VrContentMode) -> Unit,
    onVrFieldOfViewChange: (VrFieldOfView) -> Unit,
    onVrSourceLayoutChange: (VrSourceLayout) -> Unit,
    onVrProjectionChange: (VrProjection) -> Unit,
    onVrDisplayOutputChange: (VrDisplayOutput) -> Unit,
    onVrStereoAspectModeChange: (VrStereoAspectMode) -> Unit,
    onVrSourceOrientationChange: (VrSourceOrientation) -> Unit,
    onVrForwardDirectionChange: (VrForwardDirection) -> Unit,
    onVrHeadTrackingEnabledChange: (Boolean) -> Unit,
    onVrCustomHorizontalFovChange: (Float) -> Unit,
    onVrStereoParallaxPercentChange: (Float) -> Unit,
    onVrFlatScreenSizePercentChange: (Float) -> Unit,
    onVrDepthStereoEnabledChange: (Boolean) -> Unit,
    onSelectDepthModel: (String) -> Unit,
    onDownloadDepthModel: (com.example.autosrtplayer.ui.vr.depth.DepthModel) -> Unit,
    onDeleteDepthModel: (String) -> Unit,
    onGetTotalModelSizeMB: () -> Float,
    onApplyPseudoVrSbsPreset: () -> Unit
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("VR 播放模式", style = MaterialTheme.typography.titleMedium)

                Text("播放模式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VrOptionButton(
                        selected = uiState.vrConfig.contentMode == VrContentMode.Flat,
                        text = "一般",
                        onClick = { onVrContentModeChange(VrContentMode.Flat) }
                    )
                    VrOptionButton(
                        selected = uiState.vrConfig.contentMode == VrContentMode.Vr,
                        text = "VR",
                        onClick = { onVrContentModeChange(VrContentMode.Vr) }
                    )
                }

                if (uiState.vrConfig.contentMode == VrContentMode.Vr) {
                    val isFlatScreen = uiState.vrConfig.projection == VrProjection.FlatScreen

                    Button(
                        onClick = onApplyPseudoVrSbsPreset,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("一般影片 VR 眼鏡")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("頭部追蹤（G-sensor）")
                            Text(
                                "依手機方向調整 VR 視角",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = uiState.isVrHeadTrackingEnabled,
                            onCheckedChange = onVrHeadTrackingEnabledChange
                        )
                    }

                    if (!isFlatScreen) {
                        Text("視野範圍")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VrOptionButton(
                                selected = uiState.vrConfig.fieldOfView == VrFieldOfView.Fov180,
                                text = "180°",
                                onClick = { onVrFieldOfViewChange(VrFieldOfView.Fov180) }
                            )
                            VrOptionButton(
                                selected = uiState.vrConfig.fieldOfView == VrFieldOfView.Fov360,
                                text = "360°",
                                onClick = { onVrFieldOfViewChange(VrFieldOfView.Fov360) }
                            )
                            VrOptionButton(
                                selected = uiState.vrConfig.fieldOfView == VrFieldOfView.FovCustom,
                                text = "自由",
                                onClick = { onVrFieldOfViewChange(VrFieldOfView.FovCustom) }
                            )
                        }
                    }

                    if (uiState.vrConfig.fieldOfView == VrFieldOfView.FovCustom && !isFlatScreen) {
                        var customFovDraft by rememberSaveable { mutableStateOf(uiState.vrConfig.customHorizontalFovDegrees.toInt().toString()) }
                        Text("內容水平範圍")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = uiState.vrConfig.customHorizontalFovDegrees,
                                onValueChange = onVrCustomHorizontalFovChange,
                                valueRange = VrPlaybackConfig.MIN_CUSTOM_FOV..VrPlaybackConfig.MAX_CUSTOM_FOV,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customFovDraft,
                                onValueChange = { customFovDraft = it },
                                modifier = Modifier.width(80.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val parsed = customFovDraft.toFloatOrNull()
                                        if (parsed != null) {
                                            onVrCustomHorizontalFovChange(parsed)
                                        }
                                        customFovDraft = uiState.vrConfig.customHorizontalFovDegrees.toInt().toString()
                                    }
                                ),
                                suffix = { Text("°") }
                            )
                        }
                        LaunchedEffect(uiState.vrConfig.customHorizontalFovDegrees) {
                            customFovDraft = uiState.vrConfig.customHorizontalFovDegrees.toInt().toString()
                        }
                    }

                    Text("來源格式")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VrOptionButton(
                            selected = uiState.vrConfig.sourceLayout == VrSourceLayout.Monoscopic,
                            text = if (isFlatScreen) "單畫面" else "單螢幕 360°",
                            onClick = { onVrSourceLayoutChange(VrSourceLayout.Monoscopic) }
                        )
                        if (!isFlatScreen) {
                            VrOptionButton(
                                selected = uiState.vrConfig.sourceLayout == VrSourceLayout.SideBySide,
                                text = "立體左右並排",
                                onClick = { onVrSourceLayoutChange(VrSourceLayout.SideBySide) }
                            )
                            VrOptionButton(
                                selected = uiState.vrConfig.sourceLayout == VrSourceLayout.TopBottom,
                                text = "立體上下排列",
                                onClick = { onVrSourceLayoutChange(VrSourceLayout.TopBottom) }
                            )
                        }
                    }

                    Text("投影方式")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VrOptionButton(
                            selected = uiState.vrConfig.projection == VrProjection.Equirectangular,
                            text = "等距柱狀",
                            onClick = { onVrProjectionChange(VrProjection.Equirectangular) }
                        )
                        VrOptionButton(
                            selected = uiState.vrConfig.projection == VrProjection.Fisheye180,
                            text = "180° 魚眼",
                            enabled = uiState.vrConfig.fieldOfView == VrFieldOfView.Fov180,
                            onClick = { onVrProjectionChange(VrProjection.Fisheye180) }
                        )
                        VrOptionButton(
                            selected = uiState.vrConfig.projection == VrProjection.Fisheye360Dual,
                            text = "360° 雙魚眼",
                            enabled = uiState.vrConfig.fieldOfView == VrFieldOfView.Fov360,
                            onClick = { onVrProjectionChange(VrProjection.Fisheye360Dual) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VrOptionButton(
                            selected = uiState.vrConfig.projection == VrProjection.FlatScreen,
                            text = "一般影片／虛擬巨幕",
                            onClick = { onVrProjectionChange(VrProjection.FlatScreen) }
                        )
                    }

                    if (isFlatScreen) {
                        Text(
                            "此模式將一般 2D 影片放在可轉頭觀看的虛擬螢幕。左右眼為同一影片加水平偏移以營造效果，沒有真實深度或位置移動。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        var sizePercentDraft by rememberSaveable { mutableStateOf(uiState.vrConfig.flatScreenSizePercent.toInt().toString()) }
                        Text("虛擬螢幕大小")
                        Text(
                            "調整虛擬螢幕的視覺尺寸。100% 為預設大小，可縮小至 50% 或放大至 300%。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = uiState.vrConfig.flatScreenSizePercent,
                                onValueChange = onVrFlatScreenSizePercentChange,
                                valueRange = VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT..VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = sizePercentDraft,
                                onValueChange = { sizePercentDraft = it },
                                modifier = Modifier.width(80.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val parsed = sizePercentDraft.toFloatOrNull()
                                        if (parsed != null) {
                                            onVrFlatScreenSizePercentChange(parsed)
                                        }
                                        sizePercentDraft = uiState.vrConfig.flatScreenSizePercent.toInt().toString()
                                    }
                                ),
                                suffix = { Text("%") }
                            )
                        }
                        LaunchedEffect(uiState.vrConfig.flatScreenSizePercent) {
                            sizePercentDraft = uiState.vrConfig.flatScreenSizePercent.toInt().toString()
                        }
                    }

                    Text("來源方向")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VrOptionButton(
                            selected = uiState.vrConfig.sourceOrientation == VrSourceOrientation.Normal,
                            text = "正常",
                            onClick = { onVrSourceOrientationChange(VrSourceOrientation.Normal) }
                        )
                        VrOptionButton(
                            selected = uiState.vrConfig.sourceOrientation == VrSourceOrientation.FlippedVertically,
                            text = "上下翻轉",
                            onClick = { onVrSourceOrientationChange(VrSourceOrientation.FlippedVertically) }
                        )
                    }

                    if (uiState.vrConfig.projection == VrProjection.Equirectangular &&
                        uiState.vrConfig.fieldOfView == VrFieldOfView.Fov360 &&
                        !isFlatScreen) {
                        Text("正面方向")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VrOptionButton(
                                selected = uiState.vrConfig.forwardDirection == VrForwardDirection.RendererDefault,
                                text = "目前預設",
                                onClick = { onVrForwardDirectionChange(VrForwardDirection.RendererDefault) }
                            )
                            VrOptionButton(
                                selected = uiState.vrConfig.forwardDirection == VrForwardDirection.PanoramaCenter,
                                text = "全景中央",
                                onClick = { onVrForwardDirectionChange(VrForwardDirection.PanoramaCenter) }
                            )
                        }
                    }

                    Text("顯示輸出")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VrOptionButton(
                            selected = uiState.vrConfig.displayOutput == VrDisplayOutput.SingleEye,
                            text = "全螢幕單眼",
                            onClick = { onVrDisplayOutputChange(VrDisplayOutput.SingleEye) }
                        )
                        VrOptionButton(
                            selected = uiState.vrConfig.displayOutput == VrDisplayOutput.SbsGlasses,
                            text = "左右分屏（眼鏡）",
                            onClick = { onVrDisplayOutputChange(VrDisplayOutput.SbsGlasses) }
                        )
                    }

                    if (uiState.vrConfig.displayOutput == VrDisplayOutput.SbsGlasses) {
                        Text("眼鏡畫面比例")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VrOptionButton(
                                selected = uiState.vrConfig.stereoAspectMode == VrStereoAspectMode.Normal,
                                text = "一般比例",
                                onClick = { onVrStereoAspectModeChange(VrStereoAspectMode.Normal) }
                            )
                            VrOptionButton(
                                selected = uiState.vrConfig.stereoAspectMode == VrStereoAspectMode.GlassesCompensated,
                                text = "補償壓扁",
                                onClick = { onVrStereoAspectModeChange(VrStereoAspectMode.GlassesCompensated) }
                            )
                            VrOptionButton(
                                selected = uiState.vrConfig.stereoAspectMode == VrStereoAspectMode.GlassesCompensated16By9,
                                text = "8:9→16:9 補償",
                                onClick = { onVrStereoAspectModeChange(VrStereoAspectMode.GlassesCompensated16By9) }
                            )
                        }
                    }

                    if (isFlatScreen && uiState.vrConfig.displayOutput == VrDisplayOutput.SbsGlasses) {
                        val isDepthStereoEligible = uiState.vrConfig.isDepthStereoEligible()

                        var showModelDialog by rememberSaveable { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI 3D 景深（實驗性）")
                                Text(
                                    if (isDepthStereoEligible) {
                                        "即時分析影片深度，產生立體效果。會增加耗電與發熱。"
                                    } else {
                                        "需要：一般影片／虛擬巨幕 + 左右分屏（眼鏡）+ 單畫面來源"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = uiState.vrConfig.depthStereoEnabled,
                                onCheckedChange = onVrDepthStereoEnabledChange,
                                enabled = isDepthStereoEligible
                            )
                        }

                        if (uiState.vrConfig.depthStereoEnabled) {
                            val selectedModel = uiState.availableDepthModels.find { it.id == uiState.selectedDepthModelId }
                            val modelStatus = uiState.selectedDepthModelId?.let { uiState.depthModelStatuses[it] }

                            Button(
                                onClick = { showModelDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (selectedModel != null) {
                                        "深度模型：${selectedModel.name}"
                                    } else {
                                        "選擇深度模型"
                                    }
                                )
                            }

                            if (modelStatus is com.example.autosrtplayer.ui.vr.depth.ModelStatus.NotDownloaded) {
                                Text(
                                    "請先下載模型才能啟用深度效果",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (showModelDialog) {
                            DepthModelManagementDialog(
                                availableModels = uiState.availableDepthModels,
                                modelStatuses = uiState.depthModelStatuses,
                                selectedModelId = uiState.selectedDepthModelId,
                                totalModelSizeMB = onGetTotalModelSizeMB(),
                                onSelectModel = onSelectDepthModel,
                                onDownloadModel = onDownloadDepthModel,
                                onDeleteModel = onDeleteDepthModel,
                                onDismiss = { showModelDialog = false }
                            )
                        }

                        var parallaxDraft by rememberSaveable { mutableStateOf(uiState.vrConfig.stereoParallaxPercent.toString()) }
                        val strengthLabel = if (uiState.vrConfig.depthStereoEnabled && isDepthStereoEligible) {
                            "3D 景深強度"
                        } else {
                            "假立體強度"
                        }
                        Text(strengthLabel)
                        Text(
                            "0 = 無偏移（舒適），值越大左右眼水平偏移越明顯。過大可能造成不適。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = uiState.vrConfig.stereoParallaxPercent,
                                onValueChange = onVrStereoParallaxPercentChange,
                                valueRange = VrPlaybackConfig.MIN_STEREO_PARALLAX_PERCENT..VrPlaybackConfig.MAX_STEREO_PARALLAX_PERCENT,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = parallaxDraft,
                                onValueChange = { parallaxDraft = it },
                                modifier = Modifier.width(80.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val parsed = parallaxDraft.toFloatOrNull()
                                        if (parsed != null) {
                                            onVrStereoParallaxPercentChange(parsed)
                                        }
                                        parallaxDraft = uiState.vrConfig.stereoParallaxPercent.toString()
                                    }
                                ),
                                suffix = { Text("%") }
                            )
                        }
                        LaunchedEffect(uiState.vrConfig.stereoParallaxPercent) {
                            parallaxDraft = String.format("%.1f", uiState.vrConfig.stereoParallaxPercent)
                        }
                    }
                }
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("啟用 PAT Token")
                        Switch(
                            checked = uiState.isPatTokenEnabled,
                            onCheckedChange = onPatTokenEnabledChange
                        )
                    }
                    if (uiState.isPatTokenEnabled) {
                        OutlinedTextField(
                            value = uiState.patToken,
                            onValueChange = onPatTokenChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("PAT Token") },
                            placeholder = { Text("輸入 Bearer token") },
                            minLines = 1,
                            singleLine = true
                        )
                        Text(
                            "Token 會加入為 Authorization: Bearer header（surrit domain 除外）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

@Composable
private fun VrOptionButton(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
