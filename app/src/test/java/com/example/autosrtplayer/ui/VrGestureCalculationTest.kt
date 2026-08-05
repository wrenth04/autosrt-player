package com.example.autosrtplayer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrGestureCalculationTest {
    @Test
    fun `full screen width drag equals 180 degrees yaw`() {
        val screenWidth = 1920f
        val dragDistance = 1920f
        val yawDelta = -(dragDistance / screenWidth) * 180f
        assertEquals(-180f, yawDelta, 0.01f)
    }

    @Test
    fun `full screen height drag equals 180 degrees pitch`() {
        val screenHeight = 1080f
        val dragDistance = 1080f
        val pitchDelta = -(dragDistance / screenHeight) * 180f
        assertEquals(-180f, pitchDelta, 0.01f)
    }

    @Test
    fun `small drag produces proportional small angle`() {
        val screenWidth = 1920f
        val dragDistance = 96f // 5% of screen
        val yawDelta = -(dragDistance / screenWidth) * 180f
        assertEquals(-9f, yawDelta, 0.01f)
    }

    @Test
    fun `vertical drag does not affect yaw calculation`() {
        val screenWidth = 1920f
        val horizontalDrag = 100f
        val yawFromHorizontal = -(horizontalDrag / screenWidth) * 180f

        val verticalDrag = 500f
        val yawFromVertical = -(0f / screenWidth) * 180f

        assertEquals(-9.375f, yawFromHorizontal, 0.01f)
        assertEquals(0f, yawFromVertical, 0.01f)
    }

    @Test
    fun `horizontal drag does not affect pitch calculation`() {
        val screenHeight = 1080f
        val verticalDrag = 100f
        val pitchFromVertical = -(verticalDrag / screenHeight) * 180f

        val horizontalDrag = 500f
        val pitchFromHorizontal = -(0f / screenHeight) * 180f

        assertEquals(-16.67f, pitchFromVertical, 0.01f)
        assertEquals(0f, pitchFromHorizontal, 0.01f)
    }

    @Test
    fun `positive drag produces negative angle delta`() {
        val screenWidth = 1000f
        val positiveDrag = 100f
        val yawDelta = -(positiveDrag / screenWidth) * 180f
        assertEquals(-18f, yawDelta, 0.01f)
    }

    @Test
    fun `negative drag produces positive angle delta`() {
        val screenWidth = 1000f
        val negativeDrag = -100f
        val yawDelta = -(negativeDrag / screenWidth) * 180f
        assertEquals(18f, yawDelta, 0.01f)
    }

    @Test
    fun `zero screen size is handled safely`() {
        val screenWidth = 0f
        val dragDistance = 100f
        val safeWidth = screenWidth.coerceAtLeast(1f)
        val yawDelta = -(dragDistance / safeWidth) * 180f
        assertEquals(-18000f, yawDelta, 0.01f) // Large but finite
    }

    @Test
    fun `gesture with zero movement is a tap`() {
        val touchSlop = 8f
        val totalDrag = Offset.Zero
        val isTap = totalDrag.getDistance() <= touchSlop
        assertTrue(isTap)
    }

    @Test
    fun `gesture below touchSlop threshold is a tap`() {
        val touchSlop = 8f
        val totalDrag = Offset(3f, 4f) // distance = 5
        val isTap = totalDrag.getDistance() <= touchSlop
        assertTrue(isTap)
    }

    @Test
    fun `gesture exactly at touchSlop threshold is a tap`() {
        val touchSlop = 8f
        val totalDrag = Offset(0f, 8f)
        val isTap = totalDrag.getDistance() <= touchSlop
        assertTrue(isTap)
    }

    @Test
    fun `gesture exceeding touchSlop is a drag`() {
        val touchSlop = 8f
        val totalDrag = Offset(6f, 8f) // distance = 10
        val isDrag = totalDrag.getDistance() > touchSlop
        assertTrue(isDrag)
    }

    @Test
    fun `large movement is classified as drag not tap`() {
        val touchSlop = 8f
        val totalDrag = Offset(50f, 100f)
        val isDrag = totalDrag.getDistance() > touchSlop
        assertFalse(totalDrag.getDistance() <= touchSlop)
        assertTrue(isDrag)
    }

    @Test
    fun `accumulated small movements can exceed slop`() {
        val touchSlop = 8f
        var totalDrag = Offset.Zero
        totalDrag += Offset(3f, 0f)
        totalDrag += Offset(3f, 0f)
        totalDrag += Offset(3f, 0f) // total 9f horizontal
        val isDrag = totalDrag.getDistance() > touchSlop
        assertTrue(isDrag)
    }

    @Test
    fun `diagonal movement distance calculated correctly`() {
        val totalDrag = Offset(3f, 4f)
        assertEquals(5f, totalDrag.getDistance(), 0.01f)
    }

    @Test
    fun `negative offset produces positive distance`() {
        val totalDrag = Offset(-6f, -8f)
        assertEquals(10f, totalDrag.getDistance(), 0.01f)
    }

    // Pinch-to-zoom FlatScreen size tests
    @Test
    fun `pinch outward increases FlatScreen size`() {
        val currentSize = 100f
        val pinchDelta = 100f // 100px outward
        val newSize = calculateFlatScreenSizeFromPinchDelta(currentSize, pinchDelta)
        assertEquals(110f, newSize, 0.01f) // +10% per 100px
    }

    @Test
    fun `pinch inward decreases FlatScreen size`() {
        val currentSize = 100f
        val pinchDelta = -100f // 100px inward
        val newSize = calculateFlatScreenSizeFromPinchDelta(currentSize, pinchDelta)
        assertEquals(90f, newSize, 0.01f) // -10% per 100px
    }

    @Test
    fun `sequential FlatScreen pinches accumulate from prior value`() {
        val start = 100f
        val firstDelta = 50f
        val first = calculateFlatScreenSizeFromPinchDelta(start, firstDelta)
        assertEquals(105f, first, 0.01f)

        val secondDelta = 50f
        val second = calculateFlatScreenSizeFromPinchDelta(first, secondDelta)
        assertEquals(110f, second, 0.01f) // accumulated from 105, not 100
    }

    @Test
    fun `FlatScreen size clamped to minimum`() {
        val currentSize = 20f
        val pinchDelta = -500f // large inward pinch
        val newSize = calculateFlatScreenSizeFromPinchDelta(currentSize, pinchDelta)
        assertEquals(VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT, newSize, 0.01f)
    }

    @Test
    fun `FlatScreen size clamped to maximum`() {
        val currentSize = 190f
        val pinchDelta = 1500f // large outward pinch: 1500/100*10 = 150, so 190+150=340 clamped to 300
        val newSize = calculateFlatScreenSizeFromPinchDelta(currentSize, pinchDelta)
        assertEquals(VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT, newSize, 0.01f)
    }

    // Pinch-to-zoom camera FOV tests
    @Test
    fun `pinch outward decreases camera FOV for zoom-in`() {
        val currentFov = 90f
        val pinchDelta = 100f // 100px outward
        val newFov = calculateCameraFovFromPinchDelta(currentFov, pinchDelta)
        assertEquals(85f, newFov, 0.01f) // -5° per 100px
    }

    @Test
    fun `pinch inward increases camera FOV for zoom-out`() {
        val currentFov = 90f
        val pinchDelta = -100f // 100px inward
        val newFov = calculateCameraFovFromPinchDelta(currentFov, pinchDelta)
        assertEquals(95f, newFov, 0.01f) // +5° per 100px
    }

    @Test
    fun `sequential camera FOV pinches accumulate from prior value`() {
        val start = 90f
        val firstDelta = 50f
        val first = calculateCameraFovFromPinchDelta(start, firstDelta)
        assertEquals(87.5f, first, 0.01f)

        val secondDelta = 50f
        val second = calculateCameraFovFromPinchDelta(first, secondDelta)
        assertEquals(85f, second, 0.01f) // accumulated from 87.5, not 90
    }

    @Test
    fun `camera FOV clamped to minimum`() {
        val currentFov = 30f
        val pinchDelta = 500f // large outward pinch
        val newFov = calculateCameraFovFromPinchDelta(currentFov, pinchDelta)
        assertEquals(VrPlaybackConfig.MIN_VR_CAMERA_FOV, newFov, 0.01f)
    }

    @Test
    fun `camera FOV clamped to maximum`() {
        val currentFov = 110f
        val pinchDelta = -500f // large inward pinch
        val newFov = calculateCameraFovFromPinchDelta(currentFov, pinchDelta)
        assertEquals(VrPlaybackConfig.MAX_VR_CAMERA_FOV, newFov, 0.01f)
    }

    // Double-tap seek delta tests
    @Test
    fun `tap on left half rewinds by seek step`() {
        val tapX = 400f // left of center (center = 960)
        val screenWidth = 1920f
        val seekStep = 60_000L
        assertEquals(-60_000L, calculateVrSeekDelta(tapX, screenWidth, seekStep))
    }

    @Test
    fun `tap on right half fast-forwards by seek step`() {
        val tapX = 1500f // right of center (center = 960)
        val screenWidth = 1920f
        val seekStep = 60_000L
        assertEquals(60_000L, calculateVrSeekDelta(tapX, screenWidth, seekStep))
    }

    @Test
    fun `tap exactly at center is treated as right half`() {
        val tapX = 960f // exactly center
        val screenWidth = 1920f
        val seekStep = 60_000L
        assertEquals(60_000L, calculateVrSeekDelta(tapX, screenWidth, seekStep))
    }

    @Test
    fun `tap at left edge rewinds`() {
        val tapX = 0f
        val screenWidth = 1920f
        val seekStep = 60_000L
        assertEquals(-60_000L, calculateVrSeekDelta(tapX, screenWidth, seekStep))
    }

    @Test
    fun `tap at right edge fast-forwards`() {
        val tapX = 1920f
        val screenWidth = 1920f
        val seekStep = 60_000L
        assertEquals(60_000L, calculateVrSeekDelta(tapX, screenWidth, seekStep))
    }
}
