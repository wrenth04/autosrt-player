package com.example.autosrtplayer.ui

enum class VrContentMode {
    Flat,
    Vr
}

enum class VrFieldOfView {
    Fov180,
    Fov360
}

enum class VrSourceLayout {
    Monoscopic,
    SideBySide
}

enum class VrProjection {
    Equirectangular,
    Fisheye180,
    Fisheye360Dual
}

enum class VrDisplayOutput {
    SingleEye,
    SbsGlasses
}

enum class VrStereoAspectMode {
    Normal,
    GlassesCompensated
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
    val forwardDirection: VrForwardDirection = VrForwardDirection.RendererDefault
) {
    fun isValid(): Boolean {
        if (contentMode == VrContentMode.Flat) return true

        return when (projection) {
            VrProjection.Fisheye180 -> fieldOfView == VrFieldOfView.Fov180
            VrProjection.Fisheye360Dual -> fieldOfView == VrFieldOfView.Fov360
            VrProjection.Equirectangular -> true
        }
    }

    fun getMaxYawDegrees(): Float {
        return when {
            projection == VrProjection.Fisheye180 -> fisheyeFovDegrees.coerceIn(MIN_FISHEYE_FOV, MAX_FISHEYE_FOV) / 2f
            fieldOfView == VrFieldOfView.Fov180 -> 90f
            else -> 180f
        }
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
    }
}

data class VrViewAngles(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f
) {
    companion object {
        fun clampForConfig(yaw: Float, pitch: Float, config: VrPlaybackConfig): VrViewAngles {
            val clampedYaw = if (config.fieldOfView == VrFieldOfView.Fov360 &&
                                  (config.projection == VrProjection.Equirectangular ||
                                   config.projection == VrProjection.Fisheye360Dual)) {
                normalizeYaw(yaw)
            } else {
                val maxYaw = config.getMaxYawDegrees()
                yaw.coerceIn(-maxYaw, maxYaw)
            }
            val clampedPitch = pitch.coerceIn(-89f, 89f)
            return VrViewAngles(clampedYaw, clampedPitch)
        }

        fun clampForFov(yaw: Float, pitch: Float, fov: VrFieldOfView): VrViewAngles {
            val clampedYaw = when (fov) {
                VrFieldOfView.Fov180 -> yaw.coerceIn(-90f, 90f)
                VrFieldOfView.Fov360 -> normalizeYaw(yaw)
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
        }
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

        val effectiveWidth = if (displayOutput == VrDisplayOutput.SbsGlasses &&
                                  stereoAspectMode == VrStereoAspectMode.GlassesCompensated) {
            baseWidth * 2f
        } else {
            baseWidth
        }

        return effectiveWidth / screenHeight.toFloat()
    }
}
