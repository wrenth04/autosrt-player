package com.example.autosrtplayer.ui

enum class VrContentMode {
    Flat,
    Vr
}

enum class VrFieldOfView {
    Fov180,
    Fov360,
    FovCustom
}

enum class VrSourceLayout {
    Monoscopic,
    SideBySide,
    TopBottom
}

enum class VrProjection {
    Equirectangular,
    Fisheye180,
    Fisheye360Dual,
    FlatScreen
}

enum class VrDisplayOutput {
    SingleEye,
    SbsGlasses
}

enum class VrStereoAspectMode {
    Normal,
    GlassesCompensated,
    GlassesCompensated16By9
}

enum class VrSourceOrientation {
    Normal,
    FlippedVertically
}

enum class VrForwardDirection {
    RendererDefault,
    PanoramaCenter
}

data class VrPlaybackConfig(
    val contentMode: VrContentMode = VrContentMode.Flat,
    val fieldOfView: VrFieldOfView = VrFieldOfView.Fov360,
    val sourceLayout: VrSourceLayout = VrSourceLayout.Monoscopic,
    val projection: VrProjection = VrProjection.Equirectangular,
    val displayOutput: VrDisplayOutput = VrDisplayOutput.SingleEye,
    val stereoAspectMode: VrStereoAspectMode = VrStereoAspectMode.GlassesCompensated,
    val fisheyeFovDegrees: Float = DEFAULT_FISHEYE_FOV,
    val sourceOrientation: VrSourceOrientation = VrSourceOrientation.Normal,
    val forwardDirection: VrForwardDirection = VrForwardDirection.RendererDefault,
    val customHorizontalFovDegrees: Float = 180f,
    val stereoParallaxPercent: Float = DEFAULT_STEREO_PARALLAX_PERCENT,
    val flatScreenSizePercent: Float = DEFAULT_FLAT_SCREEN_SIZE_PERCENT,
    val vrCameraFovDegrees: Float = DEFAULT_VR_CAMERA_FOV,
    val depthStereoEnabled: Boolean = false
) {
    fun getEffectiveHorizontalFovDegrees(): Float {
        return when (fieldOfView) {
            VrFieldOfView.Fov180 -> 180f
            VrFieldOfView.Fov360 -> 360f
            VrFieldOfView.FovCustom -> customHorizontalFovDegrees.coerceIn(MIN_CUSTOM_FOV, MAX_CUSTOM_FOV)
        }
    }

    fun isValid(): Boolean {
        if (contentMode == VrContentMode.Flat) return true

        return when (projection) {
            VrProjection.Fisheye180 -> fieldOfView == VrFieldOfView.Fov180
            VrProjection.Fisheye360Dual -> fieldOfView == VrFieldOfView.Fov360
            VrProjection.Equirectangular -> true
            VrProjection.FlatScreen -> sourceLayout == VrSourceLayout.Monoscopic
        }
    }

    fun getMaxYawDegrees(): Float {
        return when {
            projection == VrProjection.FlatScreen -> FLAT_SCREEN_MAX_YAW
            projection == VrProjection.Fisheye180 -> fisheyeFovDegrees.coerceIn(MIN_FISHEYE_FOV, MAX_FISHEYE_FOV) / 2f
            else -> getEffectiveHorizontalFovDegrees() / 2f
        }
    }

    fun getMaxPitchDegrees(): Float {
        return when (projection) {
            VrProjection.FlatScreen -> FLAT_SCREEN_MAX_PITCH
            else -> 89f
        }
    }

    fun getEffectiveFlatScreenSizePercent(): Float {
        return flatScreenSizePercent.coerceIn(MIN_FLAT_SCREEN_SIZE_PERCENT, MAX_FLAT_SCREEN_SIZE_PERCENT)
    }

    fun getEffectiveVrCameraFovDegrees(): Float {
        return vrCameraFovDegrees.coerceIn(MIN_VR_CAMERA_FOV, MAX_VR_CAMERA_FOV)
    }

    /**
     * Returns whether the current configuration is eligible for depth-based stereo rendering.
     * Depth stereo requires VR mode, FlatScreen projection, monoscopic source, and SBS glasses output.
     */
    fun isDepthStereoEligible(): Boolean {
        return contentMode == VrContentMode.Vr &&
               projection == VrProjection.FlatScreen &&
               sourceLayout == VrSourceLayout.Monoscopic &&
               displayOutput == VrDisplayOutput.SbsGlasses
    }

    /**
     * Returns the effective strength for depth stereo effect.
     * When depth stereo is enabled and eligible, caps to a conservative comfort limit (2%).
     * Otherwise returns 0 to disable the effect.
     */
    fun getEffectiveDepthStereoStrength(): Float {
        if (!depthStereoEnabled || !isDepthStereoEligible()) {
            return 0f
        }
        val clamped = stereoParallaxPercent.coerceIn(
            MIN_STEREO_PARALLAX_PERCENT,
            MAX_STEREO_PARALLAX_PERCENT
        )
        return clamped.coerceAtMost(MAX_DEPTH_STEREO_PARALLAX_PERCENT)
    }

    fun defaultViewAngles(): VrViewAngles {
        val yaw = when (forwardDirection) {
            VrForwardDirection.RendererDefault -> 0f
            VrForwardDirection.PanoramaCenter -> 180f
        }
        return VrViewAngles.clampForConfig(yaw, 0f, this)
    }

    fun shouldFlipSourceVertically(): Boolean {
        return sourceOrientation == VrSourceOrientation.FlippedVertically
    }

    companion object {
        const val NORMAL_SCREEN_CAMERA_FOV = 65f
        const val DEFAULT_FISHEYE_FOV = 180f
        const val MIN_FISHEYE_FOV = 160f
        const val MAX_FISHEYE_FOV = 220f
        const val MIN_CUSTOM_FOV = 45f
        const val MAX_CUSTOM_FOV = 360f

        const val MIN_STEREO_PARALLAX_PERCENT = 0f
        const val DEFAULT_STEREO_PARALLAX_PERCENT = 1.5f
        const val MAX_STEREO_PARALLAX_PERCENT = 5f
        const val MAX_DEPTH_STEREO_PARALLAX_PERCENT = 2f

        const val MIN_FLAT_SCREEN_SIZE_PERCENT = 50f
        const val DEFAULT_FLAT_SCREEN_SIZE_PERCENT = 100f
        const val MAX_FLAT_SCREEN_SIZE_PERCENT = 300f

        const val MIN_VR_CAMERA_FOV = 30f
        const val DEFAULT_VR_CAMERA_FOV = 90f
        const val MAX_VR_CAMERA_FOV = 120f

        const val FLAT_SCREEN_MAX_YAW = 100f
        const val FLAT_SCREEN_MAX_PITCH = 75f

        fun youtube360Style(): VrPlaybackConfig {
            return VrPlaybackConfig(
                contentMode = VrContentMode.Vr,
                fieldOfView = VrFieldOfView.Fov360,
                sourceLayout = VrSourceLayout.Monoscopic,
                projection = VrProjection.Equirectangular,
                displayOutput = VrDisplayOutput.SingleEye,
                stereoAspectMode = VrStereoAspectMode.GlassesCompensated,
                fisheyeFovDegrees = DEFAULT_FISHEYE_FOV,
                sourceOrientation = VrSourceOrientation.Normal,
                forwardDirection = VrForwardDirection.RendererDefault
            )
        }

        fun sbs180Fisheye(): VrPlaybackConfig {
            return VrPlaybackConfig(
                contentMode = VrContentMode.Vr,
                fieldOfView = VrFieldOfView.Fov180,
                sourceLayout = VrSourceLayout.SideBySide,
                projection = VrProjection.Fisheye180,
                displayOutput = VrDisplayOutput.SbsGlasses,
                stereoAspectMode = VrStereoAspectMode.GlassesCompensated,
                fisheyeFovDegrees = DEFAULT_FISHEYE_FOV,
                sourceOrientation = VrSourceOrientation.Normal,
                forwardDirection = VrForwardDirection.RendererDefault
            )
        }

        fun pseudoVrSbs(): VrPlaybackConfig {
            return VrPlaybackConfig(
                contentMode = VrContentMode.Vr,
                fieldOfView = VrFieldOfView.Fov360,
                sourceLayout = VrSourceLayout.Monoscopic,
                projection = VrProjection.FlatScreen,
                displayOutput = VrDisplayOutput.SbsGlasses,
                stereoAspectMode = VrStereoAspectMode.GlassesCompensated,
                fisheyeFovDegrees = DEFAULT_FISHEYE_FOV,
                sourceOrientation = VrSourceOrientation.Normal,
                forwardDirection = VrForwardDirection.RendererDefault,
                customHorizontalFovDegrees = 180f,
                stereoParallaxPercent = DEFAULT_STEREO_PARALLAX_PERCENT,
                flatScreenSizePercent = DEFAULT_FLAT_SCREEN_SIZE_PERCENT
            )
        }
    }
}

data class VrViewAngles(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f
) {
    companion object {
        fun clampForConfig(yaw: Float, pitch: Float, config: VrPlaybackConfig): VrViewAngles {
            val effectiveFov = config.getEffectiveHorizontalFovDegrees()
            val clampedYaw = if (effectiveFov >= 360f &&
                                  (config.projection == VrProjection.Equirectangular ||
                                   config.projection == VrProjection.Fisheye360Dual)) {
                normalizeYaw(yaw)
            } else {
                val maxYaw = config.getMaxYawDegrees()
                yaw.coerceIn(-maxYaw, maxYaw)
            }
            val maxPitch = config.getMaxPitchDegrees()
            val clampedPitch = pitch.coerceIn(-maxPitch, maxPitch)
            return VrViewAngles(clampedYaw, clampedPitch)
        }

        fun clampForFov(yaw: Float, pitch: Float, fov: VrFieldOfView): VrViewAngles {
            val clampedYaw = when (fov) {
                VrFieldOfView.Fov180 -> yaw.coerceIn(-90f, 90f)
                VrFieldOfView.Fov360 -> normalizeYaw(yaw)
                VrFieldOfView.FovCustom -> normalizeYaw(yaw)
            }
            val clampedPitch = pitch.coerceIn(-89f, 89f)
            return VrViewAngles(clampedYaw, clampedPitch)
        }

        private fun normalizeYaw(yaw: Float): Float {
            var normalized = yaw % 360f
            if (normalized > 180f) normalized -= 360f
            if (normalized < -180f) normalized += 360f
            return normalized
        }
    }
}

data class TextureCrop(
    val uMin: Float,
    val uMax: Float,
    val vMin: Float,
    val vMax: Float
)

object VrTextureCalculator {
    fun calculateEyeCrop(
        sourceLayout: VrSourceLayout,
        isLeftEye: Boolean
    ): TextureCrop {
        return when (sourceLayout) {
            VrSourceLayout.Monoscopic -> TextureCrop(0f, 1f, 0f, 1f)
            VrSourceLayout.SideBySide -> {
                if (isLeftEye) {
                    TextureCrop(0f, 0.5f, 0f, 1f)
                } else {
                    TextureCrop(0.5f, 1f, 0f, 1f)
                }
            }
            VrSourceLayout.TopBottom -> {
                if (isLeftEye) {
                    TextureCrop(0f, 1f, 0f, 0.5f)
                } else {
                    TextureCrop(0f, 1f, 0.5f, 1f)
                }
            }
        }
    }

    /**
     * Calculate parallax offset for pseudo-VR stereo effect.
     * Returns horizontal UV offset; left eye gets negative, right eye gets positive.
     * The offset is clamped to prevent sampling outside [0, 1] range.
     */
    fun calculateParallaxOffset(
        stereoParallaxPercent: Float,
        isLeftEye: Boolean
    ): Float {
        val clampedPercent = stereoParallaxPercent.coerceIn(
            VrPlaybackConfig.MIN_STEREO_PARALLAX_PERCENT,
            VrPlaybackConfig.MAX_STEREO_PARALLAX_PERCENT
        )
        val halfOffset = (clampedPercent / 100f) / 2f
        return if (isLeftEye) -halfOffset else halfOffset
    }

    /**
     * Apply parallax offset to a base crop, ensuring result stays within [0, 1].
     */
    fun applyParallaxToCrop(baseCrop: TextureCrop, parallaxOffset: Float): TextureCrop {
        val newUMin = (baseCrop.uMin + parallaxOffset).coerceIn(0f, 1f)
        val newUMax = (baseCrop.uMax + parallaxOffset).coerceIn(0f, 1f)
        return TextureCrop(newUMin, newUMax, baseCrop.vMin, baseCrop.vMax)
    }

    fun shouldRenderTwoViewports(displayOutput: VrDisplayOutput): Boolean {
        return displayOutput == VrDisplayOutput.SbsGlasses
    }

    fun calculateViewportAspect(
        screenWidth: Int,
        screenHeight: Int,
        displayOutput: VrDisplayOutput
    ): Float {
        if (screenWidth <= 0 || screenHeight <= 0) return 1f
        val effectiveWidth = when (displayOutput) {
            VrDisplayOutput.SingleEye -> screenWidth.toFloat()
            VrDisplayOutput.SbsGlasses -> (screenWidth / 2f)
        }
        return effectiveWidth / screenHeight.toFloat()
    }

    fun calculateEyeProjectionAspect(
        screenWidth: Int,
        screenHeight: Int,
        displayOutput: VrDisplayOutput,
        stereoAspectMode: VrStereoAspectMode
    ): Float {
        if (screenWidth <= 0 || screenHeight <= 0) return 1f

        val baseWidth = when (displayOutput) {
            VrDisplayOutput.SingleEye -> screenWidth.toFloat()
            VrDisplayOutput.SbsGlasses -> (screenWidth / 2f)
        }

        val effectiveWidth = if (displayOutput == VrDisplayOutput.SbsGlasses) {
            when (stereoAspectMode) {
                VrStereoAspectMode.Normal -> baseWidth
                VrStereoAspectMode.GlassesCompensated -> baseWidth * 2f
                VrStereoAspectMode.GlassesCompensated16By9 -> baseWidth * 4f
            }
        } else {
            baseWidth
        }

        return effectiveWidth / screenHeight.toFloat()
    }
}
