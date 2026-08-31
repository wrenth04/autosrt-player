package com.example.autosrtplayer.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.autosrtplayer.data.favorites.FavoriteCodec
import com.example.autosrtplayer.data.favorites.FavoriteItem
import com.example.autosrtplayer.data.playback.MediaItemBuilder
import com.example.autosrtplayer.data.playback.PlayerFactory
import com.example.autosrtplayer.data.playlist.MissavHtmlExtractor
import com.example.autosrtplayer.data.playlist.MissavPlaylistBuilder
import com.example.autosrtplayer.data.playlist.PlaylistParser
import com.example.autosrtplayer.data.playlist.PlaylistRepository
import com.example.autosrtplayer.data.playlist.SubtitleRepository
import com.example.autosrtplayer.data.restoration.MosaicDetectorModelRepository
import com.example.autosrtplayer.data.restoration.MosaicDetectorModelSpec
import com.example.autosrtplayer.data.restoration.MosaicDetectorModelStatus
import com.example.autosrtplayer.data.restoration.RestorationModel
import com.example.autosrtplayer.data.restoration.RestorationModelRepository
import com.example.autosrtplayer.data.restoration.RestorationModelStatus
import com.example.autosrtplayer.data.todayhot.TodayHotRepository
import com.example.autosrtplayer.ui.restoration.MosaicAutoDetectionConfig
import com.example.autosrtplayer.ui.restoration.MosaicRestorationConfig
import com.example.autosrtplayer.ui.restoration.NormalizedRegion
import com.example.autosrtplayer.ui.vr.depth.DepthModel
import com.example.autosrtplayer.ui.vr.depth.DepthModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale

private data class PlaybackConfig(
    val mediaUrl: String,
    val userAgent: String?,
    val referrer: String?,
    val patToken: String?
)

@androidx.media3.common.util.UnstableApi
class PlayerViewModel(
    private val parser: PlaylistParser = PlaylistParser(),
    private val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val repository: PlaylistRepository = PlaylistRepository(sharedHttpClient),
    private val mediaItemBuilder: MediaItemBuilder = MediaItemBuilder(),
    private val subtitleRepository: SubtitleRepository = SubtitleRepository(),
    private val todayHotRepository: TodayHotRepository = TodayHotRepository(),
    private val missavHtmlExtractor: MissavHtmlExtractor = MissavHtmlExtractor(),
    private val missavPlaylistBuilder: MissavPlaylistBuilder = MissavPlaylistBuilder(),
    private val playerFactory: PlayerFactory = PlayerFactory(sharedHttpClient)
) : ViewModel() {
    companion object {
        private const val PrefsName = "autosrt_player_settings"
        private const val KeySourcePrefix = "source_prefix"
        private const val KeyFavoriteItems = "favorite_items"
        private const val KeyStartupDestination = "startup_destination"
        private const val KeyScreenOrientationMode = "screen_orientation_mode"
        private const val KeyVrContentMode = "vr_content_mode"
        private const val KeyVrFieldOfView = "vr_field_of_view"
        private const val KeyVrSourceLayout = "vr_source_layout"
        private const val KeyVrProjection = "vr_projection"
        private const val KeyVrDisplayOutput = "vr_display_output"
        private const val KeyVrStereoAspectMode = "vr_stereo_aspect_mode"
        private const val KeyVrFisheyeFov = "vr_fisheye_fov"
        private const val KeyVrSourceOrientation = "vr_source_orientation"
        private const val KeyVrForwardDirection = "vr_forward_direction"
        private const val KeyVrHeadTrackingEnabled = "vr_head_tracking_enabled"
        private const val KeyVrCustomHorizontalFov = "vr_custom_horizontal_fov"
        private const val KeyVrStereoParallaxPercent = "vr_stereo_parallax_percent"
        private const val KeyVrFlatScreenSizePercent = "vr_flat_screen_size_percent"
        private const val KeyVrCameraFov = "vr_camera_fov"
        private const val KeyVrDepthStereoEnabled = "vr_depth_stereo_enabled"
        private const val KeyVrSubtitleStereoDepthPercent = "vr_subtitle_stereo_depth_percent"
        private const val KeySelectedDepthModel = "selected_depth_model"
        private const val KeyMosaicRestorationEnabled = "mosaic_restoration_enabled"
        private const val KeyMosaicRestorationPausedOnly = "mosaic_restoration_paused_only"
        private const val KeyMosaicShowProcessingRegion = "mosaic_show_processing_region"
        private const val KeyMosaicRestorationStrength = "mosaic_restoration_strength"
        private const val KeyMosaicRegionLeft = "mosaic_region_left"
        private const val KeyMosaicRegionTop = "mosaic_region_top"
        private const val KeyMosaicRegionRight = "mosaic_region_right"
        private const val KeyMosaicRegionBottom = "mosaic_region_bottom"
        private const val KeyMosaicAutoDetectionEnabled = "mosaic_auto_detection_enabled"
        private const val KeyMosaicDetectorThreshold = "mosaic_detector_threshold"
        private const val KeyPatToken = "pat_token"
        private const val KeyPatTokenEnabled = "pat_token_enabled"
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var settingsPrefs: SharedPreferences? = null
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var activePlaybackConfig: PlaybackConfig? = null
    private var autoFullscreenPending: Boolean = false
    private var sourceResolveRequestCounter: Long = 0
    private var depthModelRepository: DepthModelRepository? = null
    private var restorationModelRepository: RestorationModelRepository? = null
    private var mosaicDetectorModelRepository: MosaicDetectorModelRepository? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (settingsPrefs == null) {
            settingsPrefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            val sourcePrefix = settingsPrefs?.getString(KeySourcePrefix, "").orEmpty()
            val favoriteItems = FavoriteCodec.decode(settingsPrefs?.getString(KeyFavoriteItems, null))
            val startupDestination = settingsPrefs
                ?.getString(KeyStartupDestination, null)
                .toStartupDestination()
            val screenOrientationMode = settingsPrefs
                ?.getString(KeyScreenOrientationMode, null)
                .toScreenOrientationMode()
            val vrConfig = loadVrConfig(settingsPrefs)
            val isVrHeadTrackingEnabled = settingsPrefs
                ?.getBoolean(KeyVrHeadTrackingEnabled, false) ?: false
            val selectedDepthModelId = settingsPrefs
                ?.getString(KeySelectedDepthModel, null)
            val restorationRepository = RestorationModelRepository(
                context = requireNotNull(appContext),
                httpClient = sharedHttpClient
            )
            restorationModelRepository = restorationRepository
            val mosaicRestorationConfig = loadMosaicRestorationConfig(settingsPrefs)
            val mosaicAutoDetectionConfig = loadMosaicAutoDetectionConfig(settingsPrefs)
            val detectorRepository = MosaicDetectorModelRepository(
                context = requireNotNull(appContext),
                httpClient = sharedHttpClient
            )
            mosaicDetectorModelRepository = detectorRepository
            val detectorSpec = MosaicDetectorModelSpec.deepMosaics()
            detectorRepository.updateConfiguration(detectorSpec)
            val patToken = settingsPrefs?.getString(KeyPatToken, "").orEmpty()
            val isPatTokenEnabled = settingsPrefs?.getBoolean(KeyPatTokenEnabled, false) ?: false

            // Initialize depth model repository
            if (depthModelRepository == null) {
                depthModelRepository = DepthModelRepository(context)
            }

            _uiState.update {
                it.copy(
                    sourcePrefix = sourcePrefix,
                    favoriteItems = favoriteItems,
                    startupDestination = startupDestination,
                    screenOrientationMode = screenOrientationMode,
                    mosaicRestorationConfig = mosaicRestorationConfig,
                    availableRestorationModels = RestorationModel.availableModels(),
                    restorationModelStatuses = restorationRepository.modelStatuses.value,
                    mosaicAutoDetectionConfig = mosaicAutoDetectionConfig,
                    mosaicDetectorModelStatus = detectorRepository.status.value,
                    vrConfig = vrConfig,
                    isVrHeadTrackingEnabled = isVrHeadTrackingEnabled,
                    selectedDepthModelId = selectedDepthModelId,
                    patToken = patToken,
                    isPatTokenEnabled = isPatTokenEnabled,
                    availableDepthModels = DepthModel.availableModels()
                )
            }

            // Observe model statuses
            viewModelScope.launch {
                depthModelRepository?.modelStatuses?.collect { statuses ->
                    _uiState.update { it.copy(depthModelStatuses = statuses) }
                }
            }
            viewModelScope.launch {
                restorationRepository.modelStatuses.collect { statuses ->
                    val status = statuses[RestorationModel.DefaultModelId]
                    val model = restorationRepository.getModel(RestorationModel.DefaultModelId)
                    val modelFile = if (status is RestorationModelStatus.Downloaded && model != null) {
                        restorationRepository.getModelFile(model)
                    } else {
                        null
                    }
                    var configToPersist: MosaicRestorationConfig? = null
                    _uiState.update {
                        val shouldDisable = it.mosaicRestorationConfig.enabled &&
                            (status is RestorationModelStatus.NotDownloaded ||
                                status is RestorationModelStatus.Error)
                        val updatedConfig = if (shouldDisable) {
                            it.mosaicRestorationConfig.copy(enabled = false).also { config ->
                                configToPersist = config
                            }
                        } else {
                            it.mosaicRestorationConfig
                        }
                        it.copy(
                            restorationModelStatuses = statuses,
                            restorationModelFile = modelFile,
                            mosaicRestorationConfig = updatedConfig,
                            isMosaicRegionEditing =
                                if (modelFile == null) false else it.isMosaicRegionEditing
                        )
                    }
                    configToPersist?.let(::persistMosaicRestorationConfig)
                }
            }
            viewModelScope.launch {
                restorationRepository.refreshModelStatuses()
            }
            viewModelScope.launch {
                detectorRepository.status.collect { status ->
                    val currentSpec = MosaicDetectorModelSpec.deepMosaics()
                    val configurationError = currentSpec.validationError()
                    val modelFile = if (
                        configurationError == null &&
                        status is MosaicDetectorModelStatus.Ready
                    ) {
                        detectorRepository.getModelFile(currentSpec)
                    } else {
                        null
                    }
                    val effectiveStatus = when {
                        configurationError != null -> MosaicDetectorModelStatus.NotConfigured
                        status is MosaicDetectorModelStatus.Ready && modelFile == null ->
                            MosaicDetectorModelStatus.NotDownloaded
                        else -> status
                    }
                    var configToPersist: MosaicAutoDetectionConfig? = null
                    _uiState.update {
                        val shouldDisable = it.mosaicAutoDetectionConfig.enabled &&
                            (effectiveStatus is MosaicDetectorModelStatus.NotConfigured ||
                                effectiveStatus is MosaicDetectorModelStatus.NotDownloaded ||
                                effectiveStatus is MosaicDetectorModelStatus.Error)
                        val updatedConfig = if (shouldDisable) {
                            it.mosaicAutoDetectionConfig.copy(enabled = false).also { config ->
                                configToPersist = config
                            }
                        } else {
                            it.mosaicAutoDetectionConfig
                        }
                        it.copy(
                            mosaicAutoDetectionConfig = updatedConfig,
                            mosaicDetectorModelStatus = effectiveStatus,
                            mosaicDetectorModelFile = modelFile
                        )
                    }
                    configToPersist?.let(::persistMosaicAutoDetectionConfig)
                }
            }
            viewModelScope.launch {
                detectorRepository.refresh(detectorSpec)
            }

            when (startupDestination) {
                StartupDestination.TodayHot -> loadTodayHot()
                StartupDestination.Favorites -> openFavorites()
                StartupDestination.Player -> Unit
            }
        }
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        initialize(context)
        val existingPlayer = player
        if (existingPlayer != null) {
            syncPlayerWithState(existingPlayer)
            return existingPlayer
        }

        val newPlayer = buildPlayer(requireNotNull(appContext))
        player = newPlayer
        attachPlayerListener(newPlayer)
        syncPlayerWithState(newPlayer)
        return newPlayer
    }

    fun onPlaylistTextChange(value: String) {
        _uiState.update { it.copy(playlistText = value) }
    }

    fun onPlaylistUrlChange(value: String) {
        _uiState.update { it.copy(playlistUrl = value) }
    }

    fun onPatTokenChange(value: String) {
        settingsPrefs?.edit()?.putString(KeyPatToken, value)?.apply()
        _uiState.update { it.copy(patToken = value) }
    }

    fun onPatTokenEnabledChange(enabled: Boolean) {
        settingsPrefs?.edit()?.putBoolean(KeyPatTokenEnabled, enabled)?.apply()
        _uiState.update { it.copy(isPatTokenEnabled = enabled) }
    }

    fun onSourceIdChange(value: String) {
        _uiState.update { it.copy(sourceId = value) }
    }

    fun onSourcePrefixChange(value: String) {
        _uiState.update { it.copy(sourcePrefix = value) }
    }

    fun saveSourcePrefix() {
        val sourcePrefix = uiState.value.sourcePrefix.trim()
        settingsPrefs?.edit()?.putString(KeySourcePrefix, sourcePrefix)?.apply()
        _uiState.update { it.copy(sourcePrefix = sourcePrefix) }
    }

    fun openFavorites() {
        _uiState.update {
            it.copy(
                isFavoritesVisible = true,
                isTodayHotVisible = false,
                isSettingsVisible = false
            )
        }
    }

    fun closeFavorites() {
        _uiState.update { it.copy(isFavoritesVisible = false) }
    }

    fun openSettings() {
        _uiState.update {
            it.copy(
                isSettingsVisible = true,
                isFavoritesVisible = false,
                isTodayHotVisible = false,
                isMosaicRegionEditing = false
            )
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsVisible = false) }
    }

    fun setStartupDestination(destination: StartupDestination) {
        settingsPrefs?.edit()?.putString(KeyStartupDestination, destination.name)?.apply()
        _uiState.update { it.copy(startupDestination = destination) }
    }

    fun toggleScreenOrientationMode() {
        val nextMode = when (_uiState.value.screenOrientationMode) {
            ScreenOrientationMode.Auto -> ScreenOrientationMode.Portrait
            ScreenOrientationMode.Portrait -> ScreenOrientationMode.Landscape
            ScreenOrientationMode.Landscape -> ScreenOrientationMode.Auto
        }
        settingsPrefs?.edit()?.putString(KeyScreenOrientationMode, nextMode.name)?.apply()
        _uiState.update { it.copy(screenOrientationMode = nextMode) }
    }

    fun setVrContentMode(mode: VrContentMode) {
        val savedConfig = loadVrConfig(settingsPrefs)
        var newConfig = if (mode == VrContentMode.Vr && _uiState.value.vrConfig.contentMode == VrContentMode.Flat) {
            // Do not reactivate a FlatScreen renderer saved by an older crashing build.
            if (savedConfig.projection == VrProjection.FlatScreen ||
                savedConfig.projection == VrProjection.StereoFlatScreen
            ) {
                VrPlaybackConfig.youtube360Style()
            } else {
                savedConfig.copy(contentMode = VrContentMode.Vr)
            }
        } else {
            _uiState.value.vrConfig.copy(contentMode = mode)
        }

        // Normalize invalid configurations
        if (!newConfig.isValid()) {
            // If still invalid, fall back to a safe default
            if (!newConfig.isValid()) {
                newConfig = VrPlaybackConfig.youtube360Style().copy(contentMode = mode)
            }
        }

        persistVrConfig(newConfig)
        _uiState.update {
            it.copy(
                vrConfig = newConfig,
                vrViewAngles = newConfig.defaultViewAngles(),
                isMosaicRegionEditing = false
            )
        }
    }

    fun setVrFieldOfView(fov: VrFieldOfView) {
        val newConfig = _uiState.value.vrConfig.copy(fieldOfView = fov)
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = newConfig.defaultViewAngles()) }
    }

    fun setVrSourceLayout(layout: VrSourceLayout) {
        val newConfig = _uiState.value.vrConfig.copy(sourceLayout = layout)
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrProjection(projection: VrProjection) {
        var newConfig = _uiState.value.vrConfig.copy(projection = projection)
        if (projection == VrProjection.FlatScreen) {
            newConfig = newConfig.copy(sourceLayout = VrSourceLayout.Monoscopic)
        } else if (projection == VrProjection.StereoFlatScreen &&
            newConfig.sourceLayout == VrSourceLayout.Monoscopic
        ) {
            newConfig = newConfig.copy(sourceLayout = VrSourceLayout.SideBySide)
        }
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = newConfig.defaultViewAngles()) }
    }

    fun setVrDisplayOutput(output: VrDisplayOutput) {
        val aspectMode = if (output == VrDisplayOutput.SbsGlasses) {
            VrStereoAspectMode.GlassesCompensated
        } else {
            _uiState.value.vrConfig.stereoAspectMode
        }
        val newConfig = _uiState.value.vrConfig.copy(
            displayOutput = output,
            stereoAspectMode = aspectMode
        )
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrStereoAspectMode(mode: VrStereoAspectMode) {
        val newConfig = _uiState.value.vrConfig.copy(stereoAspectMode = mode)
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrSourceOrientation(orientation: VrSourceOrientation) {
        val newConfig = _uiState.value.vrConfig.copy(sourceOrientation = orientation)
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrForwardDirection(direction: VrForwardDirection) {
        val newConfig = _uiState.value.vrConfig.copy(forwardDirection = direction)
        if (!newConfig.isValid()) return
        persistVrConfig(newConfig)
        val resetAngles = newConfig.defaultViewAngles()
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = resetAngles) }
    }

    fun applySbs180FisheyePreset() {
        val newConfig = VrPlaybackConfig.sbs180Fisheye()
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = newConfig.defaultViewAngles()) }
    }

    fun applyPseudoVrSbsPreset() {
        val newConfig = VrPlaybackConfig.pseudoVrSbs()
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = newConfig.defaultViewAngles()) }
    }

    fun setVrStereoParallaxPercent(percent: Float) {
        val clamped = percent.coerceIn(VrPlaybackConfig.MIN_STEREO_PARALLAX_PERCENT, VrPlaybackConfig.MAX_STEREO_PARALLAX_PERCENT)
        val newConfig = _uiState.value.vrConfig.copy(stereoParallaxPercent = clamped)
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrFlatScreenSizePercent(percent: Float) {
        val clamped = percent.coerceIn(VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT, VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT)
        val newConfig = _uiState.value.vrConfig.copy(flatScreenSizePercent = clamped)
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrFlatScreenSizePercentTransient(percent: Float) {
        val clamped = percent.coerceIn(VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT, VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT)
        val newConfig = _uiState.value.vrConfig.copy(flatScreenSizePercent = clamped)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrCameraFovDegrees(degrees: Float) {
        val clamped = degrees.coerceIn(VrPlaybackConfig.MIN_VR_CAMERA_FOV, VrPlaybackConfig.MAX_VR_CAMERA_FOV)
        val newConfig = _uiState.value.vrConfig.copy(vrCameraFovDegrees = clamped)
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrCameraFovDegreesTransient(degrees: Float) {
        val clamped = degrees.coerceIn(VrPlaybackConfig.MIN_VR_CAMERA_FOV, VrPlaybackConfig.MAX_VR_CAMERA_FOV)
        val newConfig = _uiState.value.vrConfig.copy(vrCameraFovDegrees = clamped)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setFisheyeFovDegrees(degrees: Float) {
        val clamped = degrees.coerceIn(VrPlaybackConfig.MIN_FISHEYE_FOV, VrPlaybackConfig.MAX_FISHEYE_FOV)
        val newConfig = _uiState.value.vrConfig.copy(fisheyeFovDegrees = clamped)
        persistVrConfig(newConfig)
        val clampedAngles = VrViewAngles.clampForConfig(
            _uiState.value.vrViewAngles.yawDegrees,
            _uiState.value.vrViewAngles.pitchDegrees,
            newConfig
        )
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = clampedAngles) }
    }

    fun updateVrViewAngles(yaw: Float, pitch: Float) {
        val clamped = VrViewAngles.clampForConfig(yaw, pitch, _uiState.value.vrConfig)
        _uiState.update { it.copy(vrViewAngles = clamped) }
    }

    fun resetVrViewAngles() {
        val config = _uiState.value.vrConfig
        _uiState.update { it.copy(vrViewAngles = config.defaultViewAngles()) }
    }

    fun setVrHeadTrackingEnabled(enabled: Boolean) {
        settingsPrefs?.edit()?.putBoolean(KeyVrHeadTrackingEnabled, enabled)?.apply()
        _uiState.update { it.copy(isVrHeadTrackingEnabled = enabled) }
    }

    fun setVrCustomHorizontalFovDegrees(degrees: Float) {
        val clamped = degrees.coerceIn(VrPlaybackConfig.MIN_CUSTOM_FOV, VrPlaybackConfig.MAX_CUSTOM_FOV)
        val newConfig = _uiState.value.vrConfig.copy(customHorizontalFovDegrees = clamped)
        persistVrConfig(newConfig)
        val clampedAngles = VrViewAngles.clampForConfig(
            _uiState.value.vrViewAngles.yawDegrees,
            _uiState.value.vrViewAngles.pitchDegrees,
            newConfig
        )
        _uiState.update { it.copy(vrConfig = newConfig, vrViewAngles = clampedAngles) }
    }

    fun setVrDepthStereoEnabled(enabled: Boolean) {
        val newConfig = _uiState.value.vrConfig.copy(depthStereoEnabled = enabled)
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun setVrSubtitleStereoDepthPercent(percent: Float) {
        val clamped = percent.coerceIn(
            VrPlaybackConfig.MIN_SUBTITLE_STEREO_DEPTH_PERCENT,
            VrPlaybackConfig.MAX_SUBTITLE_STEREO_DEPTH_PERCENT
        )
        val newConfig = _uiState.value.vrConfig.copy(subtitleStereoDepthPercent = clamped)
        persistVrConfig(newConfig)
        _uiState.update { it.copy(vrConfig = newConfig) }
    }

    fun selectDepthModel(modelId: String) {
        settingsPrefs?.edit()?.putString(KeySelectedDepthModel, modelId)?.apply()
        _uiState.update { it.copy(selectedDepthModelId = modelId) }
    }

    fun downloadDepthModel(model: DepthModel) {
        viewModelScope.launch {
            depthModelRepository?.downloadModel(model)
        }
    }

    fun deleteDepthModel(model: DepthModel) {
        viewModelScope.launch {
            depthModelRepository?.deleteModel(model)
            // If the deleted model was selected, clear the selection
            if (_uiState.value.selectedDepthModelId == model.id) {
                settingsPrefs?.edit()?.remove(KeySelectedDepthModel)?.apply()
                _uiState.update { it.copy(selectedDepthModelId = null) }
            }
        }
    }

    fun deleteDepthModelById(modelId: String) {
        val model = depthModelRepository?.getModel(modelId) ?: return
        deleteDepthModel(model)
    }

    fun getTotalModelSizeMB(): Float {
        val bytes = depthModelRepository?.getTotalModelSize() ?: 0L
        return bytes / (1024f * 1024f)
    }

    /**
     * Returns the selected depth model object, or null if no model is selected.
     */
    fun getSelectedDepthModel(): DepthModel? {
        val modelId = _uiState.value.selectedDepthModelId ?: return null
        return depthModelRepository?.getModel(modelId)
    }

    /**
     * Returns the file for the selected depth model, or null if not downloaded/validated.
     */
    fun getSelectedDepthModelFile(): java.io.File? {
        val model = getSelectedDepthModel() ?: return null
        return depthModelRepository?.getModelFile(model)
    }

    fun setMosaicRestorationEnabled(enabled: Boolean) {
        if (enabled) {
            if (_uiState.value.vrConfig.contentMode != VrContentMode.Flat) {
                _uiState.update {
                    it.copy(mosaicRestorationErrorMessage = "AI 局部修復目前只支援一般播放模式")
                }
                return
            }
            val model = getRestorationModel()
            val modelFile = _uiState.value.restorationModelFile
            if (model == null || modelFile == null) {
                _uiState.update {
                    it.copy(mosaicRestorationErrorMessage = "請先下載並驗證 AI 修復模型")
                }
                return
            }
        }

        val config = _uiState.value.mosaicRestorationConfig.copy(enabled = enabled).sanitized()
        persistMosaicRestorationConfig(config)
        _uiState.update {
            it.copy(
                mosaicRestorationConfig = config,
                isMosaicRegionEditing = if (enabled) it.isMosaicRegionEditing else false,
                mosaicRestorationErrorMessage = null
            )
        }
    }

    fun setMosaicRestorationStrengthTransient(strength: Float) {
        val config = _uiState.value.mosaicRestorationConfig
            .copy(strength = strength)
            .sanitized()
        _uiState.update { it.copy(mosaicRestorationConfig = config) }
    }

    fun persistMosaicRestorationStrength() {
        persistMosaicRestorationConfig(_uiState.value.mosaicRestorationConfig)
    }

    fun setMosaicRestorationPausedOnly(enabled: Boolean) {
        val config = _uiState.value.mosaicRestorationConfig
            .copy(processOnlyWhenPaused = enabled)
            .sanitized()
        persistMosaicRestorationConfig(config)
        _uiState.update {
            it.copy(
                mosaicRestorationConfig = config,
                mosaicRestorationErrorMessage = null
            )
        }
    }

    fun setMosaicProcessingRegionVisible(visible: Boolean) {
        val config = _uiState.value.mosaicRestorationConfig
            .copy(showProcessingRegion = visible)
            .sanitized()
        persistMosaicRestorationConfig(config)
        _uiState.update {
            it.copy(
                mosaicRestorationConfig = config,
                mosaicRestorationErrorMessage = null
            )
        }
    }

    fun setMosaicAutoDetectionEnabled(enabled: Boolean) {
        if (enabled &&
            (_uiState.value.mosaicDetectorModelStatus !is MosaicDetectorModelStatus.Ready ||
                _uiState.value.mosaicDetectorModelFile == null)
        ) {
            _uiState.update {
                it.copy(mosaicRestorationErrorMessage = "請先下載並驗證專用馬賽克偵測模型")
            }
            return
        }
        val config = _uiState.value.mosaicAutoDetectionConfig
            .copy(enabled = enabled)
            .sanitized()
        persistMosaicAutoDetectionConfig(config)
        _uiState.update {
            it.copy(
                mosaicAutoDetectionConfig = config,
                isMosaicRegionEditing = if (enabled) false else it.isMosaicRegionEditing,
                mosaicRestorationErrorMessage = null
            )
        }
    }

    fun setMosaicDetectorThresholdTransient(threshold: Float) {
        val config = _uiState.value.mosaicAutoDetectionConfig
            .copy(threshold = threshold)
            .sanitized()
        _uiState.update { it.copy(mosaicAutoDetectionConfig = config) }
    }

    fun persistMosaicDetectorThreshold() {
        persistMosaicAutoDetectionConfig(_uiState.value.mosaicAutoDetectionConfig)
    }

    fun downloadMosaicDetectorModel() {
        val repository = mosaicDetectorModelRepository
        val spec = MosaicDetectorModelSpec.deepMosaics()
        if (repository == null) {
            _uiState.update {
                it.copy(mosaicRestorationErrorMessage = "馬賽克偵測模型目錄尚未初始化")
            }
            return
        }
        spec.validationError()?.let { message ->
            _uiState.update { it.copy(mosaicRestorationErrorMessage = message) }
            return
        }

        _uiState.update { it.copy(mosaicRestorationErrorMessage = null) }
        viewModelScope.launch {
            repository.download(spec)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(mosaicRestorationErrorMessage = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            mosaicRestorationErrorMessage =
                                error.message ?: "馬賽克偵測模型下載失敗"
                        )
                    }
                }
        }
    }

    fun deleteMosaicDetectorModel() {
        val repository = mosaicDetectorModelRepository
        if (repository == null) {
            _uiState.update {
                it.copy(mosaicRestorationErrorMessage = "馬賽克偵測模型目錄尚未初始化")
            }
            return
        }

        val disabledConfig = _uiState.value.mosaicAutoDetectionConfig.copy(enabled = false)
        persistMosaicAutoDetectionConfig(disabledConfig)
        _uiState.update {
            it.copy(
                mosaicAutoDetectionConfig = disabledConfig,
                mosaicDetectorModelFile = null,
                mosaicRestorationErrorMessage = null
            )
        }
        viewModelScope.launch {
            repository.delete()
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            mosaicRestorationErrorMessage =
                                error.message ?: "馬賽克偵測模型刪除失敗"
                        )
                    }
                }
        }
    }

    fun startMosaicRegionEditing() {
        val model = getRestorationModel()
        val modelFile = _uiState.value.restorationModelFile
        when {
            !_uiState.value.mosaicRestorationConfig.enabled -> {
                _uiState.update {
                    it.copy(mosaicRestorationErrorMessage = "請先開啟 AI 去馬賽克再框選範圍")
                }
            }
            _uiState.value.vrConfig.contentMode != VrContentMode.Flat -> {
                _uiState.update {
                    it.copy(mosaicRestorationErrorMessage = "請先切換到一般播放模式再框選區域")
                }
            }
            _uiState.value.mediaItem == null -> {
                _uiState.update {
                    it.copy(mosaicRestorationErrorMessage = "請先載入影片再框選區域")
                }
            }
            model == null || modelFile == null -> {
                _uiState.update {
                    it.copy(mosaicRestorationErrorMessage = "請先下載並驗證 AI 修復模型")
                }
            }
            else -> {
                val restorationConfig = _uiState.value.mosaicRestorationConfig
                    .copy(showProcessingRegion = true)
                    .sanitized()
                val autoDetectionConfig = _uiState.value.mosaicAutoDetectionConfig
                    .copy(enabled = false)
                    .sanitized()
                persistMosaicRestorationConfig(restorationConfig)
                persistMosaicAutoDetectionConfig(autoDetectionConfig)
                _uiState.update {
                    it.copy(
                        isSettingsVisible = false,
                        isMosaicRegionEditing = true,
                        mosaicRestorationConfig = restorationConfig,
                        mosaicAutoDetectionConfig = autoDetectionConfig,
                        mosaicRestorationErrorMessage = null
                    )
                }
            }
        }
    }

    fun finishMosaicRegionEditing() {
        _uiState.update { it.copy(isMosaicRegionEditing = false) }
    }

    fun setMosaicRestorationRegion(region: NormalizedRegion) {
        val config = _uiState.value.mosaicRestorationConfig
            .copy(region = region)
            .sanitized()
        persistMosaicRestorationConfig(config)
        _uiState.update {
            it.copy(
                mosaicRestorationConfig = config,
                mosaicRestorationErrorMessage = null
            )
        }
    }

    fun downloadRestorationModel() {
        val repository = restorationModelRepository
        val model = getRestorationModel()
        if (repository == null || model == null) {
            _uiState.update {
                it.copy(mosaicRestorationErrorMessage = "AI 修復模型目錄尚未初始化")
            }
            return
        }

        _uiState.update { it.copy(mosaicRestorationErrorMessage = null) }
        viewModelScope.launch {
            repository.downloadModel(model)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(mosaicRestorationErrorMessage = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            mosaicRestorationErrorMessage =
                                error.message ?: "AI 修復模型下載失敗"
                        )
                    }
                }
        }
    }

    fun deleteRestorationModel() {
        val repository = restorationModelRepository
        val model = getRestorationModel()
        if (repository == null || model == null) {
            _uiState.update {
                it.copy(mosaicRestorationErrorMessage = "AI 修復模型目錄尚未初始化")
            }
            return
        }

        val disabledConfig = _uiState.value.mosaicRestorationConfig.copy(enabled = false)
        persistMosaicRestorationConfig(disabledConfig)
        _uiState.update {
            it.copy(
                mosaicRestorationConfig = disabledConfig,
                isMosaicRegionEditing = false,
                mosaicRestorationErrorMessage = null
            )
        }
        viewModelScope.launch {
            repository.deleteModel(model)
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            mosaicRestorationErrorMessage =
                                error.message ?: "AI 修復模型刪除失敗"
                        )
                    }
                }
        }
    }

    fun getRestorationModel(): RestorationModel? {
        return restorationModelRepository?.getModel(RestorationModel.DefaultModelId)
    }

    fun onMosaicRestorationError(message: String) {
        _uiState.update {
            if (it.mosaicRestorationErrorMessage == message) {
                it
            } else {
                it.copy(mosaicRestorationErrorMessage = message)
            }
        }
    }

    private fun loadMosaicRestorationConfig(
        prefs: SharedPreferences?
    ): MosaicRestorationConfig {
        fun floatPreference(key: String, default: Float): Float = runCatching {
            prefs?.getFloat(key, default) ?: default
        }.getOrDefault(default)

        return MosaicRestorationConfig(
            enabled = prefs?.getBoolean(KeyMosaicRestorationEnabled, false) ?: false,
            processOnlyWhenPaused =
                prefs?.getBoolean(KeyMosaicRestorationPausedOnly, true) ?: true,
            showProcessingRegion =
                prefs?.getBoolean(KeyMosaicShowProcessingRegion, false) ?: false,
            strength = floatPreference(
                KeyMosaicRestorationStrength,
                MosaicRestorationConfig.DefaultStrength
            ),
            region = NormalizedRegion(
                left = floatPreference(KeyMosaicRegionLeft, 0.35f),
                top = floatPreference(KeyMosaicRegionTop, 0.35f),
                right = floatPreference(KeyMosaicRegionRight, 0.65f),
                bottom = floatPreference(KeyMosaicRegionBottom, 0.65f)
            )
        ).sanitized()
    }

    private fun persistMosaicRestorationConfig(config: MosaicRestorationConfig) {
        val safeConfig = config.sanitized()
        settingsPrefs?.edit()?.apply {
            putBoolean(KeyMosaicRestorationEnabled, safeConfig.enabled)
            putBoolean(KeyMosaicRestorationPausedOnly, safeConfig.processOnlyWhenPaused)
            putBoolean(KeyMosaicShowProcessingRegion, safeConfig.showProcessingRegion)
            putFloat(KeyMosaicRestorationStrength, safeConfig.strength)
            putFloat(KeyMosaicRegionLeft, safeConfig.region.left)
            putFloat(KeyMosaicRegionTop, safeConfig.region.top)
            putFloat(KeyMosaicRegionRight, safeConfig.region.right)
            putFloat(KeyMosaicRegionBottom, safeConfig.region.bottom)
        }?.apply()
    }

    private fun loadMosaicAutoDetectionConfig(
        prefs: SharedPreferences?
    ): MosaicAutoDetectionConfig {
        val threshold = runCatching {
            prefs?.getFloat(
                KeyMosaicDetectorThreshold,
                MosaicAutoDetectionConfig.DefaultThreshold
            ) ?: MosaicAutoDetectionConfig.DefaultThreshold
        }.getOrDefault(MosaicAutoDetectionConfig.DefaultThreshold)

        return MosaicAutoDetectionConfig(
            enabled = prefs?.getBoolean(KeyMosaicAutoDetectionEnabled, false) ?: false,
            threshold = threshold
        ).sanitized()
    }

    private fun persistMosaicAutoDetectionConfig(config: MosaicAutoDetectionConfig) {
        val safeConfig = config.sanitized()
        settingsPrefs?.edit()?.apply {
            putBoolean(KeyMosaicAutoDetectionEnabled, safeConfig.enabled)
            putFloat(KeyMosaicDetectorThreshold, safeConfig.threshold)
        }?.apply()
    }

    private fun loadVrConfig(prefs: SharedPreferences?): VrPlaybackConfig {
        fun stringPreference(key: String): String? = runCatching {
            prefs?.getString(key, null)
        }.getOrNull()

        fun floatPreference(key: String, default: Float): Float = runCatching {
            prefs?.getFloat(key, default) ?: default
        }.getOrDefault(default).takeIf { it.isFinite() } ?: default

        var config = VrPlaybackConfig(
            contentMode = stringPreference(KeyVrContentMode).toVrContentMode(),
            fieldOfView = stringPreference(KeyVrFieldOfView).toVrFieldOfView(),
            sourceLayout = stringPreference(KeyVrSourceLayout).toVrSourceLayout(),
            projection = stringPreference(KeyVrProjection).toVrProjection(),
            displayOutput = stringPreference(KeyVrDisplayOutput).toVrDisplayOutput(),
            stereoAspectMode = stringPreference(KeyVrStereoAspectMode).toVrStereoAspectMode(),
            fisheyeFovDegrees = floatPreference(KeyVrFisheyeFov, VrPlaybackConfig.DEFAULT_FISHEYE_FOV),
            sourceOrientation = stringPreference(KeyVrSourceOrientation).toVrSourceOrientation(),
            forwardDirection = stringPreference(KeyVrForwardDirection).toVrForwardDirection(),
            customHorizontalFovDegrees = floatPreference(KeyVrCustomHorizontalFov, 180f),
            stereoParallaxPercent = floatPreference(
                KeyVrStereoParallaxPercent,
                VrPlaybackConfig.DEFAULT_STEREO_PARALLAX_PERCENT
            ),
            flatScreenSizePercent = floatPreference(
                KeyVrFlatScreenSizePercent,
                VrPlaybackConfig.DEFAULT_FLAT_SCREEN_SIZE_PERCENT
            ),
            vrCameraFovDegrees = floatPreference(
                KeyVrCameraFov,
                VrPlaybackConfig.DEFAULT_VR_CAMERA_FOV
            ),
            depthStereoEnabled = runCatching {
                prefs?.getBoolean(KeyVrDepthStereoEnabled, false) ?: false
            }.getOrDefault(false),
            subtitleStereoDepthPercent = floatPreference(
                KeyVrSubtitleStereoDepthPercent,
                VrPlaybackConfig.DEFAULT_SUBTITLE_STEREO_DEPTH_PERCENT
            )
        )

        if (!config.isValid()) {
            android.util.Log.w("PlayerViewModel", "Invalid VR config loaded; resetting to ordinary playback")
            config = VrPlaybackConfig()
            persistVrConfig(config)
        }

        return config
    }

    private fun persistVrConfig(config: VrPlaybackConfig) {
        settingsPrefs?.edit()?.apply {
            putString(KeyVrContentMode, config.contentMode.name)
            putString(KeyVrFieldOfView, config.fieldOfView.name)
            putString(KeyVrSourceLayout, config.sourceLayout.name)
            putString(KeyVrProjection, config.projection.name)
            putString(KeyVrDisplayOutput, config.displayOutput.name)
            putString(KeyVrStereoAspectMode, config.stereoAspectMode.name)
            putFloat(KeyVrFisheyeFov, config.fisheyeFovDegrees)
            putString(KeyVrSourceOrientation, config.sourceOrientation.name)
            putString(KeyVrForwardDirection, config.forwardDirection.name)
            putFloat(KeyVrCustomHorizontalFov, config.customHorizontalFovDegrees)
            putFloat(KeyVrStereoParallaxPercent, config.stereoParallaxPercent)
            putFloat(KeyVrFlatScreenSizePercent, config.flatScreenSizePercent)
            putFloat(KeyVrCameraFov, config.vrCameraFovDegrees)
            putBoolean(KeyVrDepthStereoEnabled, config.depthStereoEnabled)
            putFloat(KeyVrSubtitleStereoDepthPercent, config.subtitleStereoDepthPercent)
        }?.apply()
    }

    fun toggleCurrentFavorite() {
        val state = uiState.value
        val id = state.currentSourceId?.trim().orEmpty()
        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "目前影片沒有可儲存的 ID", errorType = UiErrorType.Validation) }
            return
        }

        val normalized = id.lowercase()
        val updated = state.favoriteItems.toMutableList()
        val existingIndex = updated.indexOfFirst { it.id.lowercase() == normalized }
        if (existingIndex >= 0) {
            updated.removeAt(existingIndex)
        } else {
            updated.add(0, FavoriteItem(id = id))
        }
        persistFavoriteItems(updated)
        _uiState.update { it.copy(favoriteItems = updated) }
    }

    fun removeFavorite(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val updated = uiState.value.favoriteItems.filterNot { it.id.equals(normalized, ignoreCase = true) }
        persistFavoriteItems(updated)
        _uiState.update { it.copy(favoriteItems = updated) }
    }

    fun playFavorite(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        _uiState.update { it.copy(sourceId = normalized, isFavoritesVisible = false) }
        loadFromId()
    }

    private fun persistFavoriteItems(items: List<FavoriteItem>) {
        settingsPrefs?.edit()?.putString(KeyFavoriteItems, FavoriteCodec.encode(items))?.apply()
    }

    fun exportFavorites() {
        val items = _uiState.value.favoriteItems
        if (items.isEmpty()) {
            _uiState.update { it.copy(favoriteExportMessage = "沒有可匯出的最愛項目") }
            return
        }
        val plainText = items.joinToString("\n") { it.id }
        val context = requireNotNull(appContext)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("favorites", plainText)
        clipboard.setPrimaryClip(clip)
        _uiState.update { it.copy(favoriteExportMessage = "已複製 ${items.size} 個項目到剪貼簿") }
    }

    fun importFavorites(text: String) {
        if (text.isBlank()) {
            _uiState.update { it.copy(favoriteImportMessage = "請先貼上包含 ID 的文字") }
            return
        }
        val existing = _uiState.value.favoriteItems
        val existingKeys = existing.map { it.id.lowercase(Locale.ROOT) }.toMutableSet()
        val newItems = mutableListOf<FavoriteItem>()
        text.lineSequence().forEach { line ->
            val id = line.trim()
            if (id.isBlank()) return@forEach
            val key = id.lowercase(Locale.ROOT)
            if (existingKeys.add(key)) {
                newItems.add(FavoriteItem(id = id))
            }
        }
        if (newItems.isEmpty()) {
            _uiState.update { it.copy(favoriteImportMessage = "沒有新的項目可加入（全部重複）") }
            return
        }
        val merged = newItems + existing
        persistFavoriteItems(merged)
        _uiState.update {
            it.copy(
                favoriteItems = merged,
                favoriteImportMessage = "已加入 ${newItems.size} 個項目"
            )
        }
    }

    fun clearFavoriteMessages() {
        _uiState.update { it.copy(favoriteImportMessage = null, favoriteExportMessage = null) }
    }

    private fun showPlayerShell() {
        _uiState.update {
            it.copy(
                isFavoritesVisible = false,
                isTodayHotVisible = false,
                isSettingsVisible = false
            )
        }
    }

    fun loadFromId() {
        val state = uiState.value
        val id = state.sourceId.trim()
        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入影片 ID", errorType = UiErrorType.Validation) }
            return
        }
        showPlayerShell()
        val prefix = state.sourcePrefix.trim()
        if (prefix.isBlank()) {
            startSourceResolve(id)
            return
        }
        val targetUrl = "$prefix$id.m3u8"
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    playlistUrl = targetUrl,
                    isLoading = true,
                    loadingStage = LoadingStage.ResolvingId,
                    currentRequestLabel = "ID: $id",
                    sourceResolveRequest = null,
                    errorMessage = null,
                    errorType = UiErrorType.None
                )
            }

            runCatching {
                repository.loadFromUrl(targetUrl)
            }.onSuccess { content ->
                _uiState.update {
                    it.copy(
                        playlistText = content,
                        isLoading = true,
                        loadingStage = LoadingStage.BuildingPlayer,
                        currentRequestLabel = "ID: $id",
                        sourceResolveRequest = null,
                        errorMessage = null,
                        errorType = UiErrorType.None
                    )
                }
                parseAndBuild(content, targetUrl, id)
            }.onFailure {
                startSourceResolve(id)
            }
        }
    }

    private fun startSourceResolve(id: String) {
        val requestId = ++sourceResolveRequestCounter
        val resolveUrl = "https://missav.ai/$id"
        _uiState.update {
            it.copy(
                playlistUrl = resolveUrl,
                isLoading = true,
                loadingStage = LoadingStage.ResolvingSource,
                currentRequestLabel = "ID: $id",
                sourceResolveRequest = SourceWebResolveRequest(
                    requestId = requestId,
                    id = id,
                    url = resolveUrl
                ),
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
    }

    fun loadFromSharedUrl(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        showPlayerShell()
        _uiState.update { it.copy(playlistUrl = normalized, sourceResolveRequest = null, currentSourceId = null) }
        loadFromUrl(normalized)
    }

    fun loadFromExternalId(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        showPlayerShell()
        _uiState.update { it.copy(sourceId = normalized) }
        loadFromId()
    }

    fun loadTodayHot() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTodayHotLoading = true,
                    isTodayHotVisible = true,
                    isFavoritesVisible = false,
                    isSettingsVisible = false,
                    todayHotErrorMessage = null
                )
            }
            runCatching {
                todayHotRepository.loadTodayHot()
            }.onSuccess { feed ->
                _uiState.update {
                    it.copy(
                        todayHotItems = feed.items,
                        isTodayHotLoading = false,
                        isTodayHotVisible = true,
                        todayHotErrorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isTodayHotLoading = false,
                        isTodayHotVisible = true,
                        todayHotErrorMessage = error.message ?: "載入今日熱門失敗"
                    )
                }
            }
        }
    }

    fun closeTodayHot() {
        _uiState.update { it.copy(isTodayHotVisible = false) }
    }

    fun playTodayHotCode(code: String) {
        val normalized = code.trim()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(errorMessage = "今日熱門代碼無效", errorType = UiErrorType.Validation) }
            return
        }
        _uiState.update { it.copy(sourceId = normalized, isTodayHotVisible = false, isSettingsVisible = false, isFavoritesVisible = false) }
        loadFromId()
    }

    fun loadFromText() {
        val content = uiState.value.playlistText.trim()
        if (content.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先貼上 M3U 內容", errorType = UiErrorType.Validation) }
            return
        }
        showPlayerShell()
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = LoadingStage.BuildingPlayer,
                currentRequestLabel = "M3U 文字",
                sourceResolveRequest = null,
                currentSourceId = null,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
        viewModelScope.launch {
            parseAndBuild(content)
        }
    }

    fun loadFromUrl(targetUrl: String? = null) {
        val url = targetUrl?.trim() ?: uiState.value.playlistUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請先輸入 M3U8 網址", errorType = UiErrorType.Validation) }
            return
        }

        showPlayerShell()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingStage = LoadingStage.FetchingPlaylist,
                    currentRequestLabel = url,
                    sourceResolveRequest = null,
                    currentSourceId = null,
                    errorMessage = null,
                    errorType = UiErrorType.None
                )
            }
            runCatching {
                repository.loadFromUrl(url)
            }.onSuccess { content ->
                _uiState.update { current ->
                    current.copy(
                        playlistText = content,
                        isLoading = true,
                        loadingStage = LoadingStage.BuildingPlayer,
                        currentRequestLabel = url,
                        sourceResolveRequest = null
                    )
                }
                parseAndBuild(content, url)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingStage = LoadingStage.Idle,
                        currentRequestLabel = null,
                        sourceResolveRequest = null,
                        errorType = UiErrorType.Network,
                        errorMessage = error.message ?: "載入播放清單失敗"
                    )
                }
            }
        }
    }

    fun onSourceHtmlResolved(requestId: Long, html: String, userAgent: String, finalUrl: String) {
        val state = uiState.value
        val request = state.sourceResolveRequest ?: return
        if (request.requestId != requestId) return

        val extracted = runCatching { missavHtmlExtractor.extract(html) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    sourceResolveRequest = null,
                    errorType = UiErrorType.Parse,
                    errorMessage = error.message ?: "解析失敗"
                )
            }
            return
        }
        val playlistText = missavPlaylistBuilder.build(
            id = request.id,
            detailUrl = finalUrl,
            mediaUrl = extracted.mediaUrl,
            title = extracted.title ?: request.id,
            userAgent = userAgent
        )
        _uiState.update {
            it.copy(
                playlistText = playlistText,
                playlistUrl = finalUrl,
                sourceResolveRequest = null,
                loadingStage = LoadingStage.BuildingPlayer,
                currentRequestLabel = request.id,
                errorMessage = null,
                errorType = UiErrorType.None
            )
        }
        viewModelScope.launch {
            parseAndBuild(playlistText, finalUrl, request.id)
        }
    }

    fun onSourceResolveFailed(requestId: Long, message: String) {
        val state = uiState.value
        val request = state.sourceResolveRequest ?: return
        if (request.requestId != requestId) return

        _uiState.update {
            it.copy(
                isLoading = false,
                loadingStage = LoadingStage.Idle,
                currentRequestLabel = null,
                sourceResolveRequest = null,
                errorType = UiErrorType.Network,
                errorMessage = message
            )
        }
    }

    fun setFullscreen(isFullscreen: Boolean) {
        val wasFullscreen = _uiState.value.isFullscreen
        _uiState.update { it.copy(isFullscreen = isFullscreen) }
        if (wasFullscreen && !isFullscreen) {
            player?.pause()
        }
    }

    fun toggleFullscreen() {
        val wasFullscreen = _uiState.value.isFullscreen
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
        if (wasFullscreen) {
            player?.pause()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
        player?.setPlaybackSpeed(speed)
    }

    override fun onCleared() {
        persistPlaybackState()
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        player = null
        super.onCleared()
    }

    private suspend fun parseAndBuild(content: String, playlistUrl: String? = null, sourceId: String? = null) {
        runCatching {
            val parsedEntry = parser.parse(content, playlistUrl)
            val state = uiState.value

            android.util.Log.d("PlayerViewModel", "parseAndBuild: isPatTokenEnabled=${state.isPatTokenEnabled}, hasToken=${state.patToken.isNotBlank()}")
            android.util.Log.d("PlayerViewModel", "parseAndBuild: parsedEntry.mediaUrl=${parsedEntry.mediaUrl}")
            android.util.Log.d("PlayerViewModel", "parseAndBuild: parsedEntry.patToken=${if (parsedEntry.patToken.isNullOrBlank()) "(none)" else "***${parsedEntry.patToken.takeLast(4)}"}")

            val entry = if (state.isPatTokenEnabled && state.patToken.isNotBlank()) {
                val isSurritDomain = parsedEntry.mediaUrl.contains("surrit", ignoreCase = true)
                android.util.Log.d("PlayerViewModel", "parseAndBuild: isSurritDomain=$isSurritDomain")
                if (isSurritDomain) {
                    android.util.Log.d("PlayerViewModel", "parseAndBuild: Skipping PAT token for surrit domain")
                    parsedEntry
                } else {
                    android.util.Log.d("PlayerViewModel", "parseAndBuild: Applying UI PAT token")
                    parsedEntry.copy(patToken = state.patToken)
                }
            } else {
                android.util.Log.d("PlayerViewModel", "parseAndBuild: Using parsed entry as-is (PAT not enabled or empty)")
                parsedEntry
            }

            android.util.Log.d("PlayerViewModel", "parseAndBuild: final entry.patToken=${if (entry.patToken.isNullOrBlank()) "(none)" else "***${entry.patToken.takeLast(4)}"}")

            val subtitleSource = resolveSubtitleSource(entry)
            entry to mediaItemBuilder.build(entry, subtitleSource)
        }.onSuccess { (entry, mediaItem) ->
            _uiState.update {
                it.copy(
                    parsedEntry = entry,
                    mediaItem = mediaItem,
                    currentSourceId = sourceId?.trim()?.takeIf { it.isNotBlank() },
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    sourceResolveRequest = null,
                    errorMessage = null,
                    errorType = UiErrorType.None
                )
            }
            player?.let(::syncPlayerWithState)
        }.onFailure { error ->
            activePlaybackConfig = null
            player?.stop()
            _uiState.update {
                it.copy(
                    parsedEntry = null,
                    mediaItem = null,
                    currentSourceId = null,
                    lastPlayedMediaUrl = null,
                    playbackPositionMs = 0L,
                    playWhenReady = true,
                    isLoading = false,
                    loadingStage = LoadingStage.Idle,
                    currentRequestLabel = null,
                    sourceResolveRequest = null,
                    errorType = UiErrorType.Parse,
                    errorMessage = error.message ?: "解析播放清單失敗"
                )
            }
        }
    }

    private suspend fun resolveSubtitleSource(entry: com.example.autosrtplayer.data.playlist.PlaylistEntry): String? {
        val subtitleUrl = entry.subtitleUrl?.trim().orEmpty()
        if (subtitleUrl.isBlank()) {
            return null
        }

        val subtitleUri = subtitleRepository.resolveSubtitleUri(
            context = requireNotNull(appContext),
            subtitleUrl = subtitleUrl,
            userAgent = entry.userAgent,
            referrer = entry.referrer
        )
        return subtitleUri.toString()
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val state = uiState.value
        return playerFactory.create(
            context = context,
            userAgent = state.parsedEntry?.userAgent,
            referrer = state.parsedEntry?.referrer,
            patToken = state.parsedEntry?.patToken
        )
    }

    private fun attachPlayerListener(player: ExoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && autoFullscreenPending) {
                    autoFullscreenPending = false
                    _uiState.update { state ->
                        if (state.isFullscreen) state else state.copy(isFullscreen = true)
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                persistPlaybackState()
                _uiState.update {
                    it.copy(
                        lastPlayedMediaUrl = activePlaybackConfig?.mediaUrl,
                        playbackPositionMs = player.currentPosition,
                        playWhenReady = player.playWhenReady
                    )
                }
            }
        }
        player.addListener(listener)
        playerListener = listener
    }

    private fun syncPlayerWithState(player: ExoPlayer) {
        val state = uiState.value
        val entry = state.parsedEntry ?: run {
            android.util.Log.w("PlayerViewModel", "syncPlayerWithState: no parsed entry")
            return
        }
        val mediaItem = state.mediaItem ?: run {
            android.util.Log.w("PlayerViewModel", "syncPlayerWithState: no media item")
            return
        }
        val desiredConfig = PlaybackConfig(
            mediaUrl = entry.mediaUrl,
            userAgent = entry.userAgent,
            referrer = entry.referrer,
            patToken = entry.patToken
        )
        val currentConfig = activePlaybackConfig

        android.util.Log.d("PlayerViewModel", "syncPlayerWithState: desiredUrl=${desiredConfig.mediaUrl}, currentUrl=${currentConfig?.mediaUrl}, currentMediaItem=${player.currentMediaItem?.mediaId}")

        if (currentConfig == desiredConfig && player.currentMediaItem == mediaItem) {
            android.util.Log.d("PlayerViewModel", "syncPlayerWithState: already synced, skipping")
            return
        }

        if (currentConfig != null && currentConfig != desiredConfig) {
            persistPlaybackState()
        }

        val desiredHasHeaders = !desiredConfig.userAgent.isNullOrBlank() ||
            !desiredConfig.referrer.isNullOrBlank() ||
            !desiredConfig.patToken.isNullOrBlank()
        val headersChanged = currentConfig?.userAgent != desiredConfig.userAgent ||
            currentConfig?.referrer != desiredConfig.referrer ||
            currentConfig?.patToken != desiredConfig.patToken
        val needsRecreate = if (currentConfig == null) {
            desiredHasHeaders
        } else {
            currentConfig != desiredConfig && headersChanged
        }

        android.util.Log.d("PlayerViewModel", "syncPlayerWithState: needsRecreate=$needsRecreate, hasHeaders=$desiredHasHeaders, headersChanged=$headersChanged")

        val targetPlayer = if (needsRecreate) {
            android.util.Log.i("PlayerViewModel", "syncPlayerWithState: recreating player with new headers")
            recreatePlayer(desiredConfig)
        } else {
            player
        }

        val resumeSameMedia = state.lastPlayedMediaUrl == desiredConfig.mediaUrl
        val startPositionMs = if (resumeSameMedia) state.playbackPositionMs else 0L
        val playWhenReady = if (resumeSameMedia) state.playWhenReady else true

        autoFullscreenPending = true
        activePlaybackConfig = desiredConfig
        targetPlayer.setMediaItem(mediaItem, startPositionMs)
        targetPlayer.prepare()
        targetPlayer.setPlaybackSpeed(state.playbackSpeed)
        targetPlayer.playWhenReady = playWhenReady
        _uiState.update {
            it.copy(
                lastPlayedMediaUrl = desiredConfig.mediaUrl,
                playbackPositionMs = startPositionMs,
                playWhenReady = playWhenReady
            )
        }
    }

    private fun recreatePlayer(config: PlaybackConfig): ExoPlayer {
        val context = requireNotNull(appContext)
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        val newPlayer = playerFactory.create(
            context = context,
            userAgent = config.userAgent,
            referrer = config.referrer,
            patToken = config.patToken
        )
        player = newPlayer
        attachPlayerListener(newPlayer)
        return newPlayer
    }

    private fun persistPlaybackState() {
        val currentPlayer = player ?: return
        val mediaUrl = activePlaybackConfig?.mediaUrl ?: return
        _uiState.update {
            it.copy(
                lastPlayedMediaUrl = mediaUrl,
                playbackPositionMs = currentPlayer.currentPosition,
                playWhenReady = currentPlayer.playWhenReady
            )
        }
    }
}

private fun String?.toStartupDestination(): StartupDestination {
    return runCatching {
        StartupDestination.valueOf(this.orEmpty())
    }.getOrDefault(StartupDestination.Player)
}

private fun String?.toScreenOrientationMode(): ScreenOrientationMode {
    return runCatching {
        ScreenOrientationMode.valueOf(this.orEmpty())
    }.getOrDefault(ScreenOrientationMode.Auto)
}

private fun String?.toVrContentMode(): VrContentMode {
    return runCatching {
        VrContentMode.valueOf(this.orEmpty())
    }.getOrDefault(VrContentMode.Flat)
}

private fun String?.toVrFieldOfView(): VrFieldOfView {
    return runCatching {
        VrFieldOfView.valueOf(this.orEmpty())
    }.getOrDefault(VrFieldOfView.Fov360)
}

private fun String?.toVrSourceLayout(): VrSourceLayout {
    return runCatching {
        VrSourceLayout.valueOf(this.orEmpty())
    }.getOrDefault(VrSourceLayout.Monoscopic)
}

private fun String?.toVrProjection(): VrProjection {
    return runCatching {
        VrProjection.valueOf(this.orEmpty())
    }.getOrDefault(VrProjection.Equirectangular)
}

private fun String?.toVrDisplayOutput(): VrDisplayOutput {
    return runCatching {
        VrDisplayOutput.valueOf(this.orEmpty())
    }.getOrDefault(VrDisplayOutput.SingleEye)
}

private fun String?.toVrStereoAspectMode(): VrStereoAspectMode {
    return runCatching {
        VrStereoAspectMode.valueOf(this.orEmpty())
    }.getOrDefault(VrStereoAspectMode.GlassesCompensated)
}

private fun String?.toVrSourceOrientation(): VrSourceOrientation {
    return runCatching {
        VrSourceOrientation.valueOf(this.orEmpty())
    }.getOrDefault(VrSourceOrientation.Normal)
}

private fun String?.toVrForwardDirection(): VrForwardDirection {
    return runCatching {
        VrForwardDirection.valueOf(this.orEmpty())
    }.getOrDefault(VrForwardDirection.RendererDefault)
}
