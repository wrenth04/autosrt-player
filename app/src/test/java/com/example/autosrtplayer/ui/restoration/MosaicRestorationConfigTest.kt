package com.example.autosrtplayer.ui.restoration

import com.example.autosrtplayer.data.restoration.RestorationModel
import org.junit.Assert.assertEquals
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
        assertTrue(model.downloadUrl.contains("/resolve/c6a971706797c7502945a2b4c4274fce4900d4ab/"))
        assertEquals(64, model.sha256.length)
        assertEquals(4_866_417L, model.fileSizeBytes)
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
}
