package com.example.autosrtplayer.ui.vr

import com.example.autosrtplayer.ui.VrContentMode
import com.example.autosrtplayer.ui.VrDisplayOutput
import com.example.autosrtplayer.ui.VrFieldOfView
import com.example.autosrtplayer.ui.VrForwardDirection
import com.example.autosrtplayer.ui.VrPlaybackConfig
import com.example.autosrtplayer.ui.VrProjection
import com.example.autosrtplayer.ui.VrSourceLayout
import com.example.autosrtplayer.ui.VrSourceOrientation
import com.example.autosrtplayer.ui.VrStereoAspectMode
import com.example.autosrtplayer.ui.VrTextureCalculator
import com.example.autosrtplayer.ui.VrViewAngles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrPlaybackConfigTest {
    @Test
    fun `flat mode is always valid`() {
        val config = VrPlaybackConfig(contentMode = VrContentMode.Flat)
        assertTrue(config.isValid())
    }

    @Test
    fun `youtube360Style returns monoscopic equirectangular single-eye config`() {
        val config = VrPlaybackConfig.youtube360Style()
        assertEquals(VrContentMode.Vr, config.contentMode)
        assertEquals(VrFieldOfView.Fov360, config.fieldOfView)
        assertEquals(VrSourceLayout.Monoscopic, config.sourceLayout)
        assertEquals(VrProjection.Equirectangular, config.projection)
        assertEquals(VrDisplayOutput.SingleEye, config.displayOutput)
        assertEquals(VrSourceOrientation.Normal, config.sourceOrientation)
        assertEquals(VrForwardDirection.RendererDefault, config.forwardDirection)
        assertTrue(config.isValid())
    }

    @Test
    fun `normal screen camera FOV is reasonable for non-headset viewing`() {
        val fov = VrPlaybackConfig.NORMAL_SCREEN_CAMERA_FOV
        assertTrue(fov in 60f..75f)
    }

    @Test
    fun `default VR config uses monoscopic source layout`() {
        val config = VrPlaybackConfig(contentMode = VrContentMode.Vr)
        assertEquals(VrSourceLayout.Monoscopic, config.sourceLayout)
        assertTrue(config.isValid())
    }

    @Test
    fun `equirectangular projection is valid for both FOVs`() {
        val config180 = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov180,
            projection = VrProjection.Equirectangular
        )
        assertTrue(config180.isValid())

        val config360 = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov360,
            projection = VrProjection.Equirectangular
        )
        assertTrue(config360.isValid())
    }

    @Test
    fun `fisheye180 requires 180 FOV`() {
        val valid = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov180,
            projection = VrProjection.Fisheye180
        )
        assertTrue(valid.isValid())

        val invalid = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov360,
            projection = VrProjection.Fisheye180
        )
        assertFalse(invalid.isValid())
    }

    @Test
    fun `fisheye360Dual requires 360 FOV`() {
        val valid = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov360,
            projection = VrProjection.Fisheye360Dual
        )
        assertTrue(valid.isValid())

        val invalid = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov180,
            projection = VrProjection.Fisheye360Dual
        )
        assertFalse(invalid.isValid())
    }

    @Test
    fun `FlatScreen requires Monoscopic source layout`() {
        val valid = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic
        )
        assertTrue(valid.isValid())

        val invalid = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.SideBySide
        )
        assertFalse(invalid.isValid())
    }

    @Test
    fun `pseudoVrSbs preset has correct configuration`() {
        val config = VrPlaybackConfig.pseudoVrSbs()
        assertEquals(VrContentMode.Vr, config.contentMode)
        assertEquals(VrProjection.FlatScreen, config.projection)
        assertEquals(VrSourceLayout.Monoscopic, config.sourceLayout)
        assertEquals(VrDisplayOutput.SbsGlasses, config.displayOutput)
        assertEquals(VrStereoAspectMode.GlassesCompensated, config.stereoAspectMode)
        assertEquals(VrPlaybackConfig.DEFAULT_STEREO_PARALLAX_PERCENT, config.stereoParallaxPercent, 0.01f)
        assertEquals(VrPlaybackConfig.DEFAULT_FLAT_SCREEN_SIZE_PERCENT, config.flatScreenSizePercent, 0.01f)
        assertTrue(config.isValid())
    }

    @Test
    fun `flatScreenSizePercent defaults to 100 percent`() {
        val config = VrPlaybackConfig()
        assertEquals(VrPlaybackConfig.DEFAULT_FLAT_SCREEN_SIZE_PERCENT, config.flatScreenSizePercent, 0.01f)
        assertEquals(100f, config.flatScreenSizePercent, 0.01f)
    }

    @Test
    fun `getEffectiveFlatScreenSizePercent clamps to valid range`() {
        val belowMin = VrPlaybackConfig(flatScreenSizePercent = 25f)
        assertEquals(50f, belowMin.getEffectiveFlatScreenSizePercent(), 0.01f)

        val aboveMax = VrPlaybackConfig(flatScreenSizePercent = 500f)
        assertEquals(300f, aboveMax.getEffectiveFlatScreenSizePercent(), 0.01f)

        val withinRange = VrPlaybackConfig(flatScreenSizePercent = 150f)
        assertEquals(150f, withinRange.getEffectiveFlatScreenSizePercent(), 0.01f)
    }

    @Test
    fun `flatScreenSizePercent accepts edge values`() {
        val minConfig = VrPlaybackConfig(flatScreenSizePercent = VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT)
        assertEquals(50f, minConfig.getEffectiveFlatScreenSizePercent(), 0.01f)

        val maxConfig = VrPlaybackConfig(flatScreenSizePercent = VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT)
        assertEquals(300f, maxConfig.getEffectiveFlatScreenSizePercent(), 0.01f)
    }

    @Test
    fun `FlatScreen uses independent yaw and pitch limits`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic
        )
        assertEquals(VrPlaybackConfig.FLAT_SCREEN_MAX_YAW, config.getMaxYawDegrees(), 0.01f)
        assertEquals(VrPlaybackConfig.FLAT_SCREEN_MAX_PITCH, config.getMaxPitchDegrees(), 0.01f)
    }

    @Test
    fun `FlatScreen clamps yaw and pitch within safe viewing range`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic
        )

        val result1 = VrViewAngles.clampForConfig(150f, 0f, config)
        assertEquals(VrPlaybackConfig.FLAT_SCREEN_MAX_YAW, result1.yawDegrees, 0.01f)

        val result2 = VrViewAngles.clampForConfig(-150f, 0f, config)
        assertEquals(-VrPlaybackConfig.FLAT_SCREEN_MAX_YAW, result2.yawDegrees, 0.01f)

        val result3 = VrViewAngles.clampForConfig(0f, 100f, config)
        assertEquals(VrPlaybackConfig.FLAT_SCREEN_MAX_PITCH, result3.pitchDegrees, 0.01f)

        val result4 = VrViewAngles.clampForConfig(0f, -100f, config)
        assertEquals(-VrPlaybackConfig.FLAT_SCREEN_MAX_PITCH, result4.pitchDegrees, 0.01f)
    }

    @Test
    fun `FlatScreen defaultViewAngles returns zero yaw and pitch`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic
        )
        val angles = config.defaultViewAngles()
        assertEquals(0f, angles.yawDegrees, 0.01f)
        assertEquals(0f, angles.pitchDegrees, 0.01f)
    }

    @Test
    fun `default config has normal source orientation and renderer default forward`() {
        val config = VrPlaybackConfig()
        assertEquals(VrSourceOrientation.Normal, config.sourceOrientation)
        assertEquals(VrForwardDirection.RendererDefault, config.forwardDirection)
    }

    @Test
    fun `defaultViewAngles returns zero yaw for renderer default forward`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov360,
            projection = VrProjection.Equirectangular,
            forwardDirection = VrForwardDirection.RendererDefault
        )
        val angles = config.defaultViewAngles()
        assertEquals(0f, angles.yawDegrees, 0.01f)
        assertEquals(0f, angles.pitchDegrees, 0.01f)
    }

    @Test
    fun `defaultViewAngles returns 180 yaw for panorama center forward`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov360,
            projection = VrProjection.Equirectangular,
            forwardDirection = VrForwardDirection.PanoramaCenter
        )
        val angles = config.defaultViewAngles()
        assertEquals(180f, angles.yawDegrees, 0.01f)
        assertEquals(0f, angles.pitchDegrees, 0.01f)
    }

    @Test
    fun `shouldFlipSourceVertically returns correct boolean`() {
        val normal = VrPlaybackConfig(sourceOrientation = VrSourceOrientation.Normal)
        assertFalse(normal.shouldFlipSourceVertically())

        val flipped = VrPlaybackConfig(sourceOrientation = VrSourceOrientation.FlippedVertically)
        assertTrue(flipped.shouldFlipSourceVertically())
    }
}

class VrViewAnglesTest {
    @Test
    fun `180 FOV clamps yaw to -90 to 90`() {
        val result = VrViewAngles.clampForFov(120f, 0f, VrFieldOfView.Fov180)
        assertEquals(90f, result.yawDegrees, 0.01f)

        val result2 = VrViewAngles.clampForFov(-120f, 0f, VrFieldOfView.Fov180)
        assertEquals(-90f, result2.yawDegrees, 0.01f)
    }

    @Test
    fun `360 FOV normalizes yaw to -180 to 180`() {
        val result = VrViewAngles.clampForFov(270f, 0f, VrFieldOfView.Fov360)
        assertEquals(-90f, result.yawDegrees, 0.01f)

        val result2 = VrViewAngles.clampForFov(-270f, 0f, VrFieldOfView.Fov360)
        assertEquals(90f, result2.yawDegrees, 0.01f)
    }

    @Test
    fun `pitch is always clamped to -89 to 89`() {
        val result180 = VrViewAngles.clampForFov(0f, 100f, VrFieldOfView.Fov180)
        assertEquals(89f, result180.pitchDegrees, 0.01f)

        val result360 = VrViewAngles.clampForFov(0f, -100f, VrFieldOfView.Fov360)
        assertEquals(-89f, result360.pitchDegrees, 0.01f)
    }

    @Test
    fun `360 equirectangular normalizes yaw across seam`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov360,
            projection = VrProjection.Equirectangular
        )

        val result270 = VrViewAngles.clampForConfig(270f, 0f, config)
        assertEquals(-90f, result270.yawDegrees, 0.01f)

        val resultNeg270 = VrViewAngles.clampForConfig(-270f, 0f, config)
        assertEquals(90f, resultNeg270.yawDegrees, 0.01f)

        val result190 = VrViewAngles.clampForConfig(190f, 0f, config)
        assertEquals(-170f, result190.yawDegrees, 0.01f)
    }

    @Test
    fun `180 equirectangular clamps yaw at edges`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov180,
            projection = VrProjection.Equirectangular
        )

        val result120 = VrViewAngles.clampForConfig(120f, 0f, config)
        assertEquals(90f, result120.yawDegrees, 0.01f)

        val resultNeg120 = VrViewAngles.clampForConfig(-120f, 0f, config)
        assertEquals(-90f, resultNeg120.yawDegrees, 0.01f)
    }

    @Test
    fun `fisheye 180 clamps yaw within configurable FOV`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.Fov180,
            projection = VrProjection.Fisheye180,
            fisheyeFovDegrees = 180f
        )

        val result100 = VrViewAngles.clampForConfig(100f, 0f, config)
        assertEquals(90f, result100.yawDegrees, 0.01f)
    }
}

class VrTextureCalculatorTest {
    @Test
    fun `monoscopic uses full texture for both eyes`() {
        val leftCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.Monoscopic, true)
        assertEquals(0f, leftCrop.uMin, 0.01f)
        assertEquals(1f, leftCrop.uMax, 0.01f)

        val rightCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.Monoscopic, false)
        assertEquals(0f, rightCrop.uMin, 0.01f)
        assertEquals(1f, rightCrop.uMax, 0.01f)
    }

    @Test
    fun `SBS splits texture horizontally`() {
        val leftCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.SideBySide, true)
        assertEquals(0f, leftCrop.uMin, 0.01f)
        assertEquals(0.5f, leftCrop.uMax, 0.01f)

        val rightCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.SideBySide, false)
        assertEquals(0.5f, rightCrop.uMin, 0.01f)
        assertEquals(1f, rightCrop.uMax, 0.01f)
    }

    @Test
    fun `single eye output renders once`() {
        assertFalse(VrTextureCalculator.shouldRenderTwoViewports(VrDisplayOutput.SingleEye))
    }

    @Test
    fun `SBS glasses output renders twice`() {
        assertTrue(VrTextureCalculator.shouldRenderTwoViewports(VrDisplayOutput.SbsGlasses))
    }

    @Test
    fun `viewport aspect calculation for single eye`() {
        val aspect = VrTextureCalculator.calculateViewportAspect(
            1920, 1080, VrDisplayOutput.SingleEye
        )
        assertEquals(1920f / 1080f, aspect, 0.01f)
    }

    @Test
    fun `viewport aspect calculation for SBS glasses`() {
        val aspect = VrTextureCalculator.calculateViewportAspect(
            1920, 1080, VrDisplayOutput.SbsGlasses
        )
        assertEquals((1920f / 2f) / 1080f, aspect, 0.01f)
    }

    @Test
    fun `handles zero or negative dimensions`() {
        val aspect1 = VrTextureCalculator.calculateViewportAspect(0, 1080, VrDisplayOutput.SingleEye)
        assertEquals(1f, aspect1, 0.01f)

        val aspect2 = VrTextureCalculator.calculateViewportAspect(1920, -100, VrDisplayOutput.SingleEye)
        assertEquals(1f, aspect2, 0.01f)
    }

    @Test
    fun `eye projection aspect for single eye is same as viewport aspect`() {
        val aspect = VrTextureCalculator.calculateEyeProjectionAspect(
            1920, 1080, VrDisplayOutput.SingleEye, VrStereoAspectMode.Normal
        )
        assertEquals(1920f / 1080f, aspect, 0.01f)
    }

    @Test
    fun `eye projection aspect for SBS glasses normal mode uses half width`() {
        val aspect = VrTextureCalculator.calculateEyeProjectionAspect(
            1920, 1080, VrDisplayOutput.SbsGlasses, VrStereoAspectMode.Normal
        )
        assertEquals((1920f / 2f) / 1080f, aspect, 0.01f)
    }

    @Test
    fun `eye projection aspect for SBS glasses compensated mode doubles width`() {
        val aspect = VrTextureCalculator.calculateEyeProjectionAspect(
            1920, 1080, VrDisplayOutput.SbsGlasses, VrStereoAspectMode.GlassesCompensated
        )
        assertEquals(1920f / 1080f, aspect, 0.01f)
    }

    @Test
    fun `eye projection aspect for SBS glasses 16by9 mode quadruples width from normal`() {
        val aspect = VrTextureCalculator.calculateEyeProjectionAspect(
            960, 1080, VrDisplayOutput.SbsGlasses, VrStereoAspectMode.GlassesCompensated16By9
        )
        assertEquals(1920f / 1080f, aspect, 0.01f)
    }

    @Test
    fun `glasses compensation does not affect single eye mode`() {
        val normalAspect = VrTextureCalculator.calculateEyeProjectionAspect(
            1920, 1080, VrDisplayOutput.SingleEye, VrStereoAspectMode.Normal
        )
        val compensatedAspect = VrTextureCalculator.calculateEyeProjectionAspect(
            1920, 1080, VrDisplayOutput.SingleEye, VrStereoAspectMode.GlassesCompensated
        )
        val compensated16By9Aspect = VrTextureCalculator.calculateEyeProjectionAspect(
            1920, 1080, VrDisplayOutput.SingleEye, VrStereoAspectMode.GlassesCompensated16By9
        )
        assertEquals(normalAspect, compensatedAspect, 0.01f)
        assertEquals(normalAspect, compensated16By9Aspect, 0.01f)
    }

    @Test
    fun `custom FOV mode is always valid with equirectangular`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.FovCustom,
            projection = VrProjection.Equirectangular,
            customHorizontalFovDegrees = 220f
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `custom FOV mode cannot use fisheye projections`() {
        val fisheye180 = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.FovCustom,
            projection = VrProjection.Fisheye180,
            customHorizontalFovDegrees = 180f
        )
        assertFalse(fisheye180.isValid())

        val fisheye360 = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.FovCustom,
            projection = VrProjection.Fisheye360Dual,
            customHorizontalFovDegrees = 360f
        )
        assertFalse(fisheye360.isValid())
    }

    @Test
    fun `getEffectiveHorizontalFovDegrees returns correct values`() {
        val config180 = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.Fov180
        )
        assertEquals(180f, config180.getEffectiveHorizontalFovDegrees(), 0.01f)

        val config360 = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.Fov360
        )
        assertEquals(360f, config360.getEffectiveHorizontalFovDegrees(), 0.01f)

        val configCustom = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.FovCustom,
            customHorizontalFovDegrees = 220f
        )
        assertEquals(220f, configCustom.getEffectiveHorizontalFovDegrees(), 0.01f)
    }

    @Test
    fun `custom FOV is clamped to valid range`() {
        val belowMin = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.FovCustom,
            customHorizontalFovDegrees = 10f
        )
        assertEquals(VrPlaybackConfig.MIN_CUSTOM_FOV, belowMin.getEffectiveHorizontalFovDegrees(), 0.01f)

        val aboveMax = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.FovCustom,
            customHorizontalFovDegrees = 500f
        )
        assertEquals(VrPlaybackConfig.MAX_CUSTOM_FOV, aboveMax.getEffectiveHorizontalFovDegrees(), 0.01f)

        val withinRange = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.FovCustom,
            customHorizontalFovDegrees = 200f
        )
        assertEquals(200f, withinRange.getEffectiveHorizontalFovDegrees(), 0.01f)
    }

    @Test
    fun `custom FOV less than 360 clamps yaw at half FOV`() {
        val config220 = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.FovCustom,
            projection = VrProjection.Equirectangular,
            customHorizontalFovDegrees = 220f
        )

        val result150 = VrViewAngles.clampForConfig(150f, 0f, config220)
        assertEquals(110f, result150.yawDegrees, 0.01f)

        val resultNeg150 = VrViewAngles.clampForConfig(-150f, 0f, config220)
        assertEquals(-110f, resultNeg150.yawDegrees, 0.01f)

        val resultWithin = VrViewAngles.clampForConfig(80f, 0f, config220)
        assertEquals(80f, resultWithin.yawDegrees, 0.01f)
    }

    @Test
    fun `custom FOV at 360 normalizes yaw across seam`() {
        val config360 = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            fieldOfView = VrFieldOfView.FovCustom,
            projection = VrProjection.Equirectangular,
            customHorizontalFovDegrees = 360f
        )

        val result270 = VrViewAngles.clampForConfig(270f, 0f, config360)
        assertEquals(-90f, result270.yawDegrees, 0.01f)

        val resultNeg270 = VrViewAngles.clampForConfig(-270f, 0f, config360)
        assertEquals(90f, resultNeg270.yawDegrees, 0.01f)

        val result190 = VrViewAngles.clampForConfig(190f, 0f, config360)
        assertEquals(-170f, result190.yawDegrees, 0.01f)
    }

    @Test
    fun `custom FOV getMaxYawDegrees returns half of effective FOV`() {
        val config200 = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.FovCustom,
            customHorizontalFovDegrees = 200f
        )
        assertEquals(100f, config200.getMaxYawDegrees(), 0.01f)

        val config300 = VrPlaybackConfig(
            fieldOfView = VrFieldOfView.FovCustom,
            customHorizontalFovDegrees = 300f
        )
        assertEquals(150f, config300.getMaxYawDegrees(), 0.01f)
    }

    @Test
    fun `calculateParallaxOffset returns symmetric offsets for left and right eyes`() {
        val leftOffset = VrTextureCalculator.calculateParallaxOffset(2f, isLeftEye = true)
        val rightOffset = VrTextureCalculator.calculateParallaxOffset(2f, isLeftEye = false)

        assertEquals(-0.01f, leftOffset, 0.001f)
        assertEquals(0.01f, rightOffset, 0.001f)
        assertEquals(-leftOffset, rightOffset, 0.001f)
    }

    @Test
    fun `calculateParallaxOffset with zero percent returns zero`() {
        val leftOffset = VrTextureCalculator.calculateParallaxOffset(0f, isLeftEye = true)
        val rightOffset = VrTextureCalculator.calculateParallaxOffset(0f, isLeftEye = false)

        assertEquals(0f, leftOffset, 0.001f)
        assertEquals(0f, rightOffset, 0.001f)
    }

    @Test
    fun `calculateParallaxOffset clamps to valid range`() {
        val leftOffsetMax = VrTextureCalculator.calculateParallaxOffset(100f, isLeftEye = true)
        val expectedMax = VrPlaybackConfig.MAX_STEREO_PARALLAX_PERCENT / 100f / 2f

        assertEquals(-expectedMax, leftOffsetMax, 0.001f)
    }

    @Test
    fun `applyParallaxToCrop shifts UV coordinates correctly`() {
        val baseCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.Monoscopic, isLeftEye = true)
        val parallaxOffset = 0.02f

        val shifted = VrTextureCalculator.applyParallaxToCrop(baseCrop, parallaxOffset)

        assertEquals(0.02f, shifted.uMin, 0.001f)
        assertEquals(1.0f, shifted.uMax, 0.001f)
        assertEquals(baseCrop.vMin, shifted.vMin, 0.001f)
        assertEquals(baseCrop.vMax, shifted.vMax, 0.001f)
    }

    @Test
    fun `applyParallaxToCrop clamps to UV 0-1 range`() {
        val baseCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.Monoscopic, isLeftEye = true)
        val largeNegativeOffset = -0.5f

        val shifted = VrTextureCalculator.applyParallaxToCrop(baseCrop, largeNegativeOffset)

        assertEquals(0f, shifted.uMin, 0.001f)
        assertEquals(0.5f, shifted.uMax, 0.001f)
    }

    @Test
    fun `zero parallax produces identical left and right crops`() {
        val baseCrop = VrTextureCalculator.calculateEyeCrop(VrSourceLayout.Monoscopic, isLeftEye = true)
        val leftOffset = VrTextureCalculator.calculateParallaxOffset(0f, isLeftEye = true)
        val rightOffset = VrTextureCalculator.calculateParallaxOffset(0f, isLeftEye = false)

        val leftCrop = VrTextureCalculator.applyParallaxToCrop(baseCrop, leftOffset)
        val rightCrop = VrTextureCalculator.applyParallaxToCrop(baseCrop, rightOffset)

        assertEquals(leftCrop.uMin, rightCrop.uMin, 0.001f)
        assertEquals(leftCrop.uMax, rightCrop.uMax, 0.001f)
    }

    @Test
    fun `depthStereoEnabled defaults to false`() {
        val config = VrPlaybackConfig()
        assertFalse(config.depthStereoEnabled)
    }

    @Test
    fun `depth stereo is not eligible in flat mode`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Flat,
            depthStereoEnabled = true
        )
        assertFalse(config.isDepthStereoEligible())
    }

    @Test
    fun `depth stereo requires FlatScreen projection`() {
        val equirect = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.Equirectangular,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            depthStereoEnabled = true
        )
        assertFalse(equirect.isDepthStereoEligible())

        val flatScreen = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            depthStereoEnabled = true
        )
        assertTrue(flatScreen.isDepthStereoEligible())
    }

    @Test
    fun `depth stereo requires monoscopic source`() {
        val sbs = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.SideBySide,
            displayOutput = VrDisplayOutput.SbsGlasses,
            depthStereoEnabled = true
        )
        assertFalse(sbs.isDepthStereoEligible())

        val mono = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            depthStereoEnabled = true
        )
        assertTrue(mono.isDepthStereoEligible())
    }

    @Test
    fun `depth stereo requires SBS glasses output`() {
        val singleEye = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SingleEye,
            depthStereoEnabled = true
        )
        assertFalse(singleEye.isDepthStereoEligible())

        val sbsGlasses = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            depthStereoEnabled = true
        )
        assertTrue(sbsGlasses.isDepthStereoEligible())
    }

    @Test
    fun `effective depth stereo strength is zero when disabled`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            stereoParallaxPercent = 3f,
            depthStereoEnabled = false
        )
        assertEquals(0f, config.getEffectiveDepthStereoStrength(), 0.001f)
    }

    @Test
    fun `effective depth stereo strength is zero when not eligible`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Flat,
            stereoParallaxPercent = 3f,
            depthStereoEnabled = true
        )
        assertEquals(0f, config.getEffectiveDepthStereoStrength(), 0.001f)
    }

    @Test
    fun `effective depth stereo strength is capped at 2 percent`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            stereoParallaxPercent = 5f,
            depthStereoEnabled = true
        )
        val effective = config.getEffectiveDepthStereoStrength()
        assertEquals(VrPlaybackConfig.MAX_DEPTH_STEREO_PARALLAX_PERCENT, effective, 0.001f)
        assertTrue(effective <= 2f)
    }

    @Test
    fun `effective depth stereo strength allows values below cap`() {
        val config = VrPlaybackConfig(
            contentMode = VrContentMode.Vr,
            projection = VrProjection.FlatScreen,
            sourceLayout = VrSourceLayout.Monoscopic,
            displayOutput = VrDisplayOutput.SbsGlasses,
            stereoParallaxPercent = 1.5f,
            depthStereoEnabled = true
        )
        assertEquals(1.5f, config.getEffectiveDepthStereoStrength(), 0.001f)
    }

    @Test
    fun `pseudoVrSbs preset does not enable depth stereo by default`() {
        val config = VrPlaybackConfig.pseudoVrSbs()
        assertFalse(config.depthStereoEnabled)
    }

    @Test
    fun `youtube360Style preset does not enable depth stereo by default`() {
        val config = VrPlaybackConfig.youtube360Style()
        assertFalse(config.depthStereoEnabled)
    }

    @Test
    fun `sbs180Fisheye preset does not enable depth stereo by default`() {
        val config = VrPlaybackConfig.sbs180Fisheye()
        assertFalse(config.depthStereoEnabled)
    }
}

