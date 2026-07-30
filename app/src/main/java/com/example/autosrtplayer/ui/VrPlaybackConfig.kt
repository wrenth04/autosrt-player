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

data class VrPlaybackConfig(
    val contentMode: VrContentMode = VrContentMode.Flat,
    val fieldOfView: VrFieldOfView = VrFieldOfView.Fov360,
    val sourceLayout: VrSourceLayout = VrSourceLayout.Monoscopic,
    val projection: VrProjection = VrProjection.Equirectangular,
    val displayOutput: VrDisplayOutput = VrDisplayOutput.SingleEye
) {
    fun isValid(): Boolean {
        if (contentMode == VrContentMode.Flat) return true

        return when (projection) {
            VrProjection.Fisheye180 -> fieldOfView == VrFieldOfView.Fov180
            VrProjection.Fisheye360Dual -> fieldOfView == VrFieldOfView.Fov360
            VrProjection.Equirectangular -> true
        }
    }

    companion object {
        const val NORMAL_SCREEN_CAMERA_FOV = 65f

        fun youtube360Style(): VrPlaybackConfig {
            return VrPlaybackConfig(
                contentMode = VrContentMode.Vr,
                fieldOfView = VrFieldOfView.Fov360,
                sourceLayout = VrSourceLayout.Monoscopic,
                projection = VrProjection.Equirectangular,
                displayOutput = VrDisplayOutput.SingleEye
            )
        }
    }
}

data class VrViewAngles(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f
) {
    companion object {
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
}
