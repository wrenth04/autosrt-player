package com.example.autosrtplayer.ui.restoration

import com.example.autosrtplayer.data.restoration.RestorationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicRestorationConfigTest {
    @Test
    fun `restoration model catalog is valid`() {
        assertTrue(RestorationModel.validateCatalog().isEmpty())
    }

    @Test
    fun `default model download is pinned and integrity checked`() {
        val model = RestorationModel.availableModels().single()

        assertEquals(RestorationModel.DefaultModelId, model.id)
        assertTrue(model.downloadUrl.contains("/resolve/cead5e065f22d817078a451350975f80e9a93f7d/"))
        assertEquals(64, model.sha256.length)
        assertEquals(
            "a30cd9bd518afc7169edf09aa64824ea61d9a24aa641433c7db5cc298585d45b",
            model.sha256
        )
        assertEquals(213_449_721L, model.fileSizeBytes)
        assertEquals("input", model.inputTensorName)
        assertEquals("input.17", model.previousInputTensorName)
        assertEquals("output", model.outputTensorName)
        assertEquals(256, model.inputSize)
        assertEquals(5, model.temporalFrameCount)
    }

    @Test
    fun `region from reversed points is ordered and clamped`() {
        val region = NormalizedRegion.fromPoints(
            firstX = 1.2f,
            firstY = 0.8f,
            secondX = -0.2f,
            secondY = 0.1f
        )

        assertEquals(0f, region.left, 0.001f)
        assertEquals(0.1f, region.top, 0.001f)
        assertEquals(1f, region.right, 0.001f)
        assertEquals(0.8f, region.bottom, 0.001f)
    }

    @Test
    fun `tiny region expands to minimum selectable size`() {
        val region = NormalizedRegion(
            left = 0.5f,
            top = 0.5f,
            right = 0.51f,
            bottom = 0.51f
        ).sanitized()

        assertEquals(NormalizedRegion.MinimumRegionSize, region.width, 0.001f)
        assertEquals(NormalizedRegion.MinimumRegionSize, region.height, 0.001f)
        assertTrue(region.left >= 0f && region.right <= 1f)
        assertTrue(region.top >= 0f && region.bottom <= 1f)
    }

    @Test
    fun `invalid strength and coordinates fall back to safe values`() {
        val config = MosaicRestorationConfig(
            strength = Float.NaN,
            region = NormalizedRegion(
                left = Float.NaN,
                top = Float.NEGATIVE_INFINITY,
                right = Float.POSITIVE_INFINITY,
                bottom = Float.NaN
            )
        ).sanitized()

        assertEquals(MosaicRestorationConfig.DefaultStrength, config.strength, 0.001f)
        assertTrue(config.region.left in 0f..1f)
        assertTrue(config.region.top in 0f..1f)
        assertTrue(config.region.right in 0f..1f)
        assertTrue(config.region.bottom in 0f..1f)
        assertTrue(config.region.width >= NormalizedRegion.MinimumRegionSize)
        assertTrue(config.region.height >= NormalizedRegion.MinimumRegionSize)
    }

    @Test
    fun `inference size keeps landscape aspect within model limit`() {
        val size = calculateRestorationInferenceSize(
            sourceWidth = 320,
            sourceHeight = 160,
            maxEdge = 96
        )

        assertEquals(96, size.width)
        assertEquals(48, size.height)
    }

    @Test
    fun `source region uses decoded video dimensions rather than player view dimensions`() {
        val source = calculateRestorationSourceRegion(
            region = NormalizedRegion(),
            videoWidth = 1920,
            videoHeight = 1080
        )

        assertEquals(672, source.left)
        assertEquals(378, source.top)
        assertEquals(1248, source.right)
        assertEquals(702, source.bottom)
    }

    @Test
    fun `inference size keeps portrait aspect within model limit`() {
        val size = calculateRestorationInferenceSize(
            sourceWidth = 90,
            sourceHeight = 180,
            maxEdge = 96
        )

        assertEquals(48, size.width)
        assertEquals(96, size.height)
    }

    @Test
    fun `inference size never exceeds a non-aligned model limit`() {
        val size = calculateRestorationInferenceSize(
            sourceWidth = 200,
            sourceHeight = 100,
            maxEdge = 95
        )

        assertTrue(size.width <= 95)
        assertTrue(size.height <= 95)
    }

    @Test
    fun `auto detection config normalizes model fields and threshold`() {
        val config = MosaicAutoDetectionConfig(
            threshold = Float.NaN
        ).sanitized()

        assertEquals(MosaicAutoDetectionConfig.DefaultThreshold, config.threshold, 0.001f)
    }

    @Test
    fun `restoration defaults to paused processing`() {
        assertTrue(MosaicRestorationConfig().processOnlyWhenPaused)
    }

    @Test
    fun `square restoration region remains square in video pixels`() {
        val region = calculateSquareRestorationRegion(
            region = NormalizedRegion(0.4f, 0.4f, 0.6f, 0.6f),
            videoWidth = 1920,
            videoHeight = 1080
        )
        val pixels = calculateRestorationSourceRegion(region, 1920, 1080)

        assertEquals(pixels.width, pixels.height)
        assertTrue(pixels.left >= 0 && pixels.right <= 1920)
        assertTrue(pixels.top >= 0 && pixels.bottom <= 1080)
    }

    @Test
    fun `square restoration region clamps at video edge`() {
        val region = calculateSquareRestorationRegion(
            region = NormalizedRegion(0.92f, 0.86f, 1f, 1f),
            videoWidth = 1920,
            videoHeight = 1080
        )
        val pixels = calculateRestorationSourceRegion(region, 1920, 1080)

        assertEquals(pixels.width, pixels.height)
        assertEquals(1920, pixels.right)
        assertEquals(1080, pixels.bottom)
    }

    @Test
    fun `region overlap reports intersection over union`() {
        val overlap = regionIntersectionOverUnion(
            first = NormalizedRegion(0.1f, 0.1f, 0.5f, 0.5f),
            second = NormalizedRegion(0.3f, 0.3f, 0.7f, 0.7f)
        )

        assertEquals(0.04f / 0.28f, overlap, 0.001f)
        assertEquals(
            0f,
            regionIntersectionOverUnion(
                NormalizedRegion(0f, 0f, 0.2f, 0.2f),
                NormalizedRegion(0.8f, 0.8f, 1f, 1f)
            ),
            0.001f
        )
    }

    @Test
    fun `tracked region movement is smoothed`() {
        val previous = NormalizedRegion(0.1f, 0.2f, 0.3f, 0.4f)
        val current = NormalizedRegion(0.3f, 0.4f, 0.5f, 0.6f)

        val tracked = smoothTrackedRegion(previous, current, smoothing = 0.5f)

        assertEquals(0.2f, tracked.left, 0.001f)
        assertEquals(0.3f, tracked.top, 0.001f)
        assertEquals(0.4f, tracked.right, 0.001f)
        assertEquals(0.5f, tracked.bottom, 0.001f)
    }

    @Test
    fun `tracker holds short misses then clears region`() {
        val tracker = MosaicRegionTracker(smoothing = 0.5f, missedDetectionLimit = 3)
        val detected = NormalizedRegion(0.1f, 0.2f, 0.3f, 0.4f)

        assertRegionEquals(detected, tracker.update(detected))
        assertRegionEquals(detected, tracker.update(null))
        assertRegionEquals(detected, tracker.update(null))
        assertNull(tracker.update(null))
    }

    @Test
    fun `tracker resets miss count after a detection`() {
        val tracker = MosaicRegionTracker(smoothing = 1f, missedDetectionLimit = 2)
        val first = NormalizedRegion(0.1f, 0.1f, 0.3f, 0.3f)
        val second = NormalizedRegion(0.4f, 0.4f, 0.6f, 0.6f)

        tracker.update(first)
        tracker.update(null)
        assertRegionEquals(second, tracker.update(second))
        assertRegionEquals(second, tracker.update(null))
        assertNull(tracker.update(null))
    }

    private fun assertRegionEquals(
        expected: NormalizedRegion,
        actual: NormalizedRegion?
    ) {
        requireNotNull(actual)
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }
}
