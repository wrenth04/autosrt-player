package com.example.autosrtplayer.data.restoration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepMosaicsTensorCodecTest {
    @Test
    fun `ARGB pixels convert to normalized planar RGB`() {
        val values = argbToNormalizedRgbNchw(
            intArrayOf(
                0xffff0000.toInt(),
                0xff00ff00.toInt()
            )
        )

        assertArrayEquals(
            floatArrayOf(
                1f, -1f,
                -1f, 1f,
                -1f, -1f
            ),
            values,
            0.001f
        )
    }

    @Test
    fun `temporal frames are arranged as channel frame height width`() {
        val stream = buildDeepMosaicsTemporalStream(
            normalizedFrames = listOf(
                floatArrayOf(1f, 2f, 3f),
                floatArrayOf(4f, 5f, 6f)
            ),
            pixelCount = 1
        )

        assertArrayEquals(
            floatArrayOf(1f, 4f, 2f, 5f, 3f, 6f),
            stream,
            0f
        )
    }

    @Test
    fun `decoded output is opaque in center and transparent at edge`() {
        val pixelCount = 9
        val values = FloatArray(pixelCount * 3)
        repeat(pixelCount) { index ->
            values[index] = 1f
            values[pixelCount + index] = 0f
            values[pixelCount * 2 + index] = -1f
        }

        val pixels = normalizedRgbToFeatheredArgb(
            values = values,
            width = 3,
            height = 3
        )

        assertEquals(0, pixels.first() ushr 24)
        assertEquals(0xffff8000.toInt(), pixels[4])
    }

    @Test
    fun `detector alpha mask replaces rectangular edge feather`() {
        val pixelCount = 9
        val values = FloatArray(pixelCount * 3)

        val pixels = normalizedRgbToFeatheredArgb(
            values = values,
            width = 3,
            height = 3,
            alphaMask = FloatArray(pixelCount) { 1f }
        )

        assertEquals(255, pixels.first() ushr 24)
        assertEquals(255, pixels.last() ushr 24)
    }

    @Test
    fun `mosaic probability mask is cropped and thresholded`() {
        val probabilities = FloatArray(16)
        for (y in 1..2) {
            for (x in 1..2) {
                probabilities[y * 4 + x] = 0.9f
            }
        }

        val alpha = createFeatheredMosaicMask(
            mask = MosaicProbabilityMask(
                probabilities = probabilities,
                width = 4,
                height = 4
            ),
            regionLeft = 0f,
            regionTop = 0f,
            regionRight = 1f,
            regionBottom = 1f,
            threshold = 0.5f,
            outputSize = 4,
            blurRadius = 0
        )

        assertEquals(0f, alpha[0], 0f)
        assertEquals(1f, alpha[5], 0f)
        assertEquals(1f, alpha[10], 0f)
        assertEquals(0f, alpha[15], 0f)
    }

    @Test
    fun `mosaic mask feathering softens component boundary`() {
        val probabilities = FloatArray(25)
        probabilities[2 * 5 + 2] = 1f

        val alpha = createFeatheredMosaicMask(
            mask = MosaicProbabilityMask(probabilities, width = 5, height = 5),
            regionLeft = 0f,
            regionTop = 0f,
            regionRight = 1f,
            regionBottom = 1f,
            threshold = 0.5f,
            outputSize = 5,
            blurRadius = 1
        )

        assertEquals(1f / 9f, alpha[2 * 5 + 2], 0.001f)
        assertEquals(1f / 9f, alpha[2 * 5 + 1], 0.001f)
        assertEquals(0f, alpha[0], 0f)
    }
}
