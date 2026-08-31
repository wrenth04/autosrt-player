package com.example.autosrtplayer.ui.restoration

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class NormalizedRegion(
    val left: Float = 0.35f,
    val top: Float = 0.35f,
    val right: Float = 0.65f,
    val bottom: Float = 0.65f
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    fun sanitized(minimumSize: Float = MinimumRegionSize): NormalizedRegion {
        val safeMinimum = minimumSize.coerceIn(0.01f, 1f)
        val orderedLeft = min(left.finiteOr(0.35f), right.finiteOr(0.65f)).coerceIn(0f, 1f)
        val orderedRight = max(left.finiteOr(0.35f), right.finiteOr(0.65f)).coerceIn(0f, 1f)
        val orderedTop = min(top.finiteOr(0.35f), bottom.finiteOr(0.65f)).coerceIn(0f, 1f)
        val orderedBottom = max(top.finiteOr(0.35f), bottom.finiteOr(0.65f)).coerceIn(0f, 1f)

        val centerX = (orderedLeft + orderedRight) / 2f
        val centerY = (orderedTop + orderedBottom) / 2f
        val safeWidth = (orderedRight - orderedLeft).coerceAtLeast(safeMinimum)
        val safeHeight = (orderedBottom - orderedTop).coerceAtLeast(safeMinimum)
        val safeLeft = (centerX - safeWidth / 2f).coerceIn(0f, 1f - safeWidth)
        val safeTop = (centerY - safeHeight / 2f).coerceIn(0f, 1f - safeHeight)

        return NormalizedRegion(
            left = safeLeft,
            top = safeTop,
            right = safeLeft + safeWidth,
            bottom = safeTop + safeHeight
        )
    }

    companion object {
        const val MinimumRegionSize = 0.08f

        fun fromPoints(
            firstX: Float,
            firstY: Float,
            secondX: Float,
            secondY: Float
        ): NormalizedRegion {
            return NormalizedRegion(
                left = min(firstX, secondX),
                top = min(firstY, secondY),
                right = max(firstX, secondX),
                bottom = max(firstY, secondY)
            ).sanitized()
        }
    }
}

data class MosaicRestorationConfig(
    val enabled: Boolean = false,
    val processOnlyWhenPaused: Boolean = false,
    val showProcessingRegion: Boolean = false,
    val showProcessingProgress: Boolean = false,
    val strength: Float = DefaultStrength,
    val region: NormalizedRegion = NormalizedRegion()
) {
    fun sanitized(): MosaicRestorationConfig {
        return copy(
            strength = strength.finiteOr(DefaultStrength).coerceIn(MinStrength, MaxStrength),
            region = region.sanitized()
        )
    }

    companion object {
        const val MinStrength = 0.1f
        const val DefaultStrength = 1f
        const val MaxStrength = 1f
    }
}

data class MosaicAutoDetectionConfig(
    val enabled: Boolean = false,
    val threshold: Float = DefaultThreshold
) {
    fun sanitized(): MosaicAutoDetectionConfig {
        return copy(
            threshold = threshold.finiteOr(DefaultThreshold).coerceIn(
                MinThreshold,
                MaxThreshold
            )
        )
    }

    companion object {
        const val MinThreshold = 0.1f
        const val DefaultThreshold = 0.25f
        const val MaxThreshold = 0.9f
    }
}

internal fun smoothTrackedRegion(
    previous: NormalizedRegion?,
    current: NormalizedRegion,
    smoothing: Float = 0.4f
): NormalizedRegion {
    val safeCurrent = current.sanitized()
    val safePrevious = previous?.sanitized() ?: return safeCurrent
    val amount = smoothing.coerceIn(0f, 1f)

    fun blend(old: Float, new: Float): Float = old + (new - old) * amount

    return NormalizedRegion(
        left = blend(safePrevious.left, safeCurrent.left),
        top = blend(safePrevious.top, safeCurrent.top),
        right = blend(safePrevious.right, safeCurrent.right),
        bottom = blend(safePrevious.bottom, safeCurrent.bottom)
    ).sanitized()
}

data class InferenceSize(
    val width: Int,
    val height: Int
)

data class PixelRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

fun calculateRestorationSourceRegion(
    region: NormalizedRegion,
    videoWidth: Int,
    videoHeight: Int
): PixelRegion {
    require(videoWidth > 0 && videoHeight > 0) { "Video dimensions must be positive" }
    val safe = region.sanitized()
    val left = (safe.left * videoWidth).roundToInt().coerceIn(0, videoWidth - 1)
    val top = (safe.top * videoHeight).roundToInt().coerceIn(0, videoHeight - 1)
    val right = (safe.right * videoWidth).roundToInt().coerceIn(left + 1, videoWidth)
    val bottom = (safe.bottom * videoHeight).roundToInt().coerceIn(top + 1, videoHeight)
    return PixelRegion(left, top, right, bottom)
}

fun calculateSquareRestorationRegion(
    region: NormalizedRegion,
    videoWidth: Int,
    videoHeight: Int
): NormalizedRegion {
    require(videoWidth > 0 && videoHeight > 0) { "Video dimensions must be positive" }
    val source = calculateRestorationSourceRegion(region, videoWidth, videoHeight)
    val minimumSide = ceil(
        max(videoWidth, videoHeight) * NormalizedRegion.MinimumRegionSize
    ).toInt()
    val side = max(max(source.width, source.height), minimumSide)
        .coerceAtMost(min(videoWidth, videoHeight))
    val centerX = (source.left + source.right) / 2f
    val centerY = (source.top + source.bottom) / 2f
    val left = (centerX - side / 2f)
        .roundToInt()
        .coerceIn(0, videoWidth - side)
    val top = (centerY - side / 2f)
        .roundToInt()
        .coerceIn(0, videoHeight - side)

    return NormalizedRegion(
        left = left.toFloat() / videoWidth,
        top = top.toFloat() / videoHeight,
        right = (left + side).toFloat() / videoWidth,
        bottom = (top + side).toFloat() / videoHeight
    )
}

internal fun regionIntersectionOverUnion(
    first: NormalizedRegion,
    second: NormalizedRegion
): Float {
    val a = first.sanitized()
    val b = second.sanitized()
    val intersectionWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
    val intersectionHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
    val intersection = intersectionWidth * intersectionHeight
    val union = a.width * a.height + b.width * b.height - intersection
    return if (union > 0f) intersection / union else 0f
}

fun calculateRestorationInferenceSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxEdge: Int,
    minEdge: Int = 16
): InferenceSize {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
    require(maxEdge >= minEdge && minEdge > 0) { "Inference edge limits are invalid" }

    val scale = min(1f, maxEdge.toFloat() / max(sourceWidth, sourceHeight))
    val width = alignToFour(
        value = (sourceWidth * scale).roundToInt(),
        minEdge = minEdge,
        maxEdge = maxEdge
    )
    val height = alignToFour(
        value = (sourceHeight * scale).roundToInt(),
        minEdge = minEdge,
        maxEdge = maxEdge
    )
    return InferenceSize(width, height)
}

private fun alignToFour(value: Int, minEdge: Int, maxEdge: Int): Int {
    return ((value + 2) / 4 * 4).coerceIn(minEdge, maxEdge)
}

private fun Float.finiteOr(default: Float): Float {
    return if (isFinite()) this else default
}
