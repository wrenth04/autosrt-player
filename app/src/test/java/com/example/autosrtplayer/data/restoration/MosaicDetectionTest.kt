package com.example.autosrtplayer.data.restoration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicDetectionTest {
    @Test
    fun `largest connected mask component is selected`() {
        val width = 20
        val height = 10
        val probabilities = FloatArray(width * height)
        probabilities.fillRect(width, 1..2, 1..2, 0.9f)
        probabilities.fillRect(width, 10..14, 4..6, 0.8f)

        val region = requireNotNull(
            findLargestMosaicRegion(
                probabilities = probabilities,
                width = width,
                height = height,
                threshold = 0.5f,
                expansionFactor = 1f
            )
        )

        assertEquals(0.5f, region.left, 0.001f)
        assertEquals(0.4f, region.top, 0.001f)
        assertEquals(0.75f, region.right, 0.001f)
        assertEquals(0.7f, region.bottom, 0.001f)
        assertEquals(0.8f, region.confidence, 0.001f)
        assertEquals(0.075f, region.areaFraction, 0.001f)
    }

    @Test
    fun `isolated mask noise below minimum area is ignored`() {
        val probabilities = FloatArray(100 * 100)
        probabilities[5050] = 0.95f

        val region = findLargestMosaicRegion(
            probabilities = probabilities,
            width = 100,
            height = 100,
            threshold = 0.5f
        )

        assertNull(region)
    }

    @Test
    fun `near full frame masks are rejected`() {
        val probabilities = FloatArray(10 * 10) { 0.9f }

        val region = findLargestMosaicRegion(
            probabilities = probabilities,
            width = 10,
            height = 10,
            threshold = 0.5f
        )

        assertNull(region)
    }

    @Test
    fun `sparse component spanning most of frame is rejected`() {
        val probabilities = FloatArray(10 * 10)
        repeat(10) { index ->
            probabilities[index * 10 + index] = 0.9f
        }

        val region = findLargestMosaicRegion(
            probabilities = probabilities,
            width = 10,
            height = 10,
            threshold = 0.5f
        )

        assertNull(region)
    }

    @Test
    fun `expanded region is clamped to mask bounds`() {
        val width = 10
        val probabilities = FloatArray(width * 10)
        probabilities.fillRect(width, 0..1, 0..1, 0.9f)

        val region = requireNotNull(
            findLargestMosaicRegion(
                probabilities = probabilities,
                width = width,
                height = 10,
                threshold = 0.5f,
                expansionFactor = 2f
            )
        )

        assertEquals(0f, region.left, 0.001f)
        assertEquals(0f, region.top, 0.001f)
        assertEquals(0.4f, region.right, 0.001f)
        assertEquals(0.4f, region.bottom, 0.001f)
    }

    @Test
    fun `default crop expansion matches DeepMosaics youknow model`() {
        val width = 10
        val probabilities = FloatArray(width * 10)
        probabilities.fillRect(width, 4..5, 4..5, 0.9f)

        val region = requireNotNull(
            findLargestMosaicRegion(
                probabilities = probabilities,
                width = width,
                height = 10,
                threshold = 0.5f
            )
        )

        assertEquals(0.3f, region.right - region.left, 0.001f)
        assertEquals(0.3f, region.bottom - region.top, 0.001f)
    }

    @Test
    fun `detector model spec requires valid https URL and sha`() {
        val validSha = "ab".repeat(32)
        assertNull(
            MosaicDetectorModelSpec(
                downloadUrl = "https://example.com/models/detector.onnx",
                sha256 = validSha.uppercase()
            ).validationError()
        )
        assertTrue(
            MosaicDetectorModelSpec(
                downloadUrl = "http://example.com/detector.onnx",
                sha256 = validSha
            ).validationError()?.contains("HTTPS") == true
        )
        assertTrue(
            MosaicDetectorModelSpec(
                downloadUrl = "https://",
                sha256 = validSha
            ).validationError()?.contains("HTTPS") == true
        )
        assertTrue(
            MosaicDetectorModelSpec(
                downloadUrl = "https://example.com/detector.onnx",
                sha256 = "not-a-sha"
            ).validationError()?.contains("SHA-256") == true
        )
    }

    @Test
    fun `DeepMosaics detector preset is pinned`() {
        val spec = MosaicDetectorModelSpec.deepMosaics()

        assertNull(spec.validationError())
        assertTrue(
            spec.downloadUrl.contains(
                "/resolve/cead5e065f22d817078a451350975f80e9a93f7d/"
            )
        )
        assertEquals(
            "fa16f91573aa09973cf3dc91e2fc1113f55fde1adb46d65548946cf5c88b4cbe",
            spec.sha256
        )
        assertEquals(47_540_486L, MosaicDetectorModelSpec.DeepMosaicsFileSizeBytes)
    }

    private fun FloatArray.fillRect(
        width: Int,
        xRange: IntRange,
        yRange: IntRange,
        value: Float
    ) {
        for (y in yRange) {
            for (x in xRange) {
                this[y * width + x] = value
            }
        }
    }
}
