package com.example.autosrtplayer.data.restoration

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal fun argbToNormalizedRgbNchw(pixels: IntArray): FloatArray {
    val pixelCount = pixels.size
    return FloatArray(pixelCount * 3).also { output ->
        for (index in pixels.indices) {
            val pixel = pixels[index]
            output[index] = ((pixel ushr 16) and 0xff) / 127.5f - 1f
            output[pixelCount + index] = ((pixel ushr 8) and 0xff) / 127.5f - 1f
            output[pixelCount * 2 + index] = (pixel and 0xff) / 127.5f - 1f
        }
    }
}

internal fun buildDeepMosaicsTemporalStream(
    normalizedFrames: List<FloatArray>,
    pixelCount: Int
): FloatArray {
    require(normalizedFrames.isNotEmpty()) { "At least one frame is required" }
    require(pixelCount > 0) { "Pixel count must be positive" }
    require(normalizedFrames.all { it.size == pixelCount * 3 }) {
        "Temporal frame length is invalid"
    }

    val frameCount = normalizedFrames.size
    return FloatArray(pixelCount * 3 * frameCount).also { output ->
        for (channel in 0 until 3) {
            for (frameIndex in normalizedFrames.indices) {
                val source = normalizedFrames[frameIndex]
                val sourceOffset = channel * pixelCount
                val destinationOffset =
                    channel * frameCount * pixelCount + frameIndex * pixelCount
                source.copyInto(
                    destination = output,
                    destinationOffset = destinationOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + pixelCount
                )
            }
        }
    }
}

internal fun calculateNormalizedRgbChangeFraction(
    original: FloatArray,
    restored: FloatArray,
    alphaMask: FloatArray? = null
): Float {
    require(original.size == restored.size && original.size % 3 == 0) {
        "RGB tensor lengths must match"
    }
    val pixelCount = original.size / 3
    require(alphaMask == null || alphaMask.size == pixelCount) {
        "Alpha mask length is invalid"
    }

    var weightedDifference = 0f
    var totalWeight = 0f
    for (pixelIndex in 0 until pixelCount) {
        val weight = alphaMask?.get(pixelIndex)?.coerceIn(0f, 1f) ?: 1f
        if (weight == 0f) continue
        for (channel in 0 until 3) {
            val index = channel * pixelCount + pixelIndex
            require(original[index].isFinite() && restored[index].isFinite()) {
                "RGB tensor contains a non-finite value"
            }
            weightedDifference +=
                (abs(restored[index] - original[index]).coerceAtMost(2f) / 2f) * weight
            totalWeight += weight
        }
    }
    return if (totalWeight > 0f) {
        (weightedDifference / totalWeight).coerceIn(0f, 1f)
    } else {
        0f
    }
}

internal fun normalizedRgbToFeatheredArgb(
    values: FloatArray,
    width: Int,
    height: Int,
    alphaMask: FloatArray? = null,
    featherFraction: Float = 0.1f
): IntArray {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    val pixelCount = width * height
    require(values.size == pixelCount * 3) { "RGB tensor length is invalid" }
    require(alphaMask == null || alphaMask.size == pixelCount) {
        "Alpha mask length is invalid"
    }
    require(featherFraction in 0f..0.5f) { "Feather fraction is invalid" }
    val featherPixels = (min(width, height) * featherFraction)
        .roundToInt()
        .coerceAtLeast(1)

    return IntArray(pixelCount) { index ->
        val x = index % width
        val y = index / width
        val edgeDistance = min(min(x, width - 1 - x), min(y, height - 1 - y))
        val alpha = alphaMask?.get(index)?.coerceIn(0f, 1f)
            ?: (edgeDistance.toFloat() / featherPixels).coerceIn(0f, 1f)
        val red = normalizedToByte(values[index])
        val green = normalizedToByte(values[pixelCount + index])
        val blue = normalizedToByte(values[pixelCount * 2 + index])
        ((alpha * 255f).roundToInt() shl 24) or
            (red shl 16) or
            (green shl 8) or
            blue
    }
}

internal fun createFeatheredMosaicMask(
    mask: MosaicProbabilityMask,
    regionLeft: Float,
    regionTop: Float,
    regionRight: Float,
    regionBottom: Float,
    threshold: Float,
    outputSize: Int,
    blurRadius: Int = (outputSize * 0.025f).roundToInt().coerceAtLeast(1)
): FloatArray {
    require(mask.width > 0 && mask.height > 0) { "Mask dimensions must be positive" }
    require(mask.probabilities.size == mask.width * mask.height) {
        "Mask dimensions do not match its values"
    }
    require(regionLeft in 0f..1f && regionTop in 0f..1f) {
        "Mask crop origin is invalid"
    }
    require(regionRight in regionLeft..1f && regionBottom in regionTop..1f) {
        "Mask crop bounds are invalid"
    }
    require(threshold in 0f..1f) { "Mask threshold is invalid" }
    require(outputSize > 0) { "Output size must be positive" }
    require(blurRadius >= 0) { "Blur radius cannot be negative" }

    val binary = FloatArray(outputSize * outputSize)
    for (y in 0 until outputSize) {
        val normalizedY = regionTop +
            (y + 0.5f) / outputSize * (regionBottom - regionTop)
        val sourceY = (normalizedY * mask.height)
            .toInt()
            .coerceIn(0, mask.height - 1)
        for (x in 0 until outputSize) {
            val normalizedX = regionLeft +
                (x + 0.5f) / outputSize * (regionRight - regionLeft)
            val sourceX = (normalizedX * mask.width)
                .toInt()
                .coerceIn(0, mask.width - 1)
            if (mask.probabilities[sourceY * mask.width + sourceX] >= threshold) {
                binary[y * outputSize + x] = 1f
            }
        }
    }
    if (blurRadius == 0) return binary

    val horizontal = FloatArray(binary.size)
    for (y in 0 until outputSize) {
        var sum = 0f
        for (x in -blurRadius..blurRadius) {
            if (x in 0 until outputSize) {
                sum += binary[y * outputSize + x]
            }
        }
        for (x in 0 until outputSize) {
            horizontal[y * outputSize + x] = sum / (blurRadius * 2 + 1)
            val removedX = x - blurRadius
            val addedX = x + blurRadius + 1
            if (removedX in 0 until outputSize) {
                sum -= binary[y * outputSize + removedX]
            }
            if (addedX in 0 until outputSize) {
                sum += binary[y * outputSize + addedX]
            }
        }
    }

    return FloatArray(binary.size).also { feathered ->
        for (x in 0 until outputSize) {
            var sum = 0f
            for (y in -blurRadius..blurRadius) {
                if (y in 0 until outputSize) {
                    sum += horizontal[y * outputSize + x]
                }
            }
            for (y in 0 until outputSize) {
                feathered[y * outputSize + x] =
                    (sum / (blurRadius * 2 + 1)).coerceIn(0f, 1f)
                val removedY = y - blurRadius
                val addedY = y + blurRadius + 1
                if (removedY in 0 until outputSize) {
                    sum -= horizontal[removedY * outputSize + x]
                }
                if (addedY in 0 until outputSize) {
                    sum += horizontal[addedY * outputSize + x]
                }
            }
        }
    }
}

private fun normalizedToByte(value: Float): Int {
    require(value.isFinite()) { "RGB tensor contains a non-finite value" }
    return ((value.coerceIn(-1f, 1f) + 1f) * 127.5f).roundToInt()
}
