package com.example.autosrtplayer.ui.restoration

import kotlin.math.roundToLong

internal fun calculateDeepMosaicsFrameIntervalMs(frameRate: Float?): Long {
    val safeFrameRate = frameRate?.takeIf {
        it.isFinite() && it in MinimumSupportedFrameRate..MaximumSupportedFrameRate
    } ?: DefaultFrameRate
    return (DeepMosaicsFrameStride * 1_000f / safeFrameRate)
        .roundToLong()
        .coerceIn(MinimumFrameIntervalMs, MaximumFrameIntervalMs)
}

internal fun calculateDeepMosaicsSamplePositions(
    centerPositionMs: Long,
    durationMs: Long?,
    frameIntervalMs: Long,
    frameCount: Int
): List<Long> {
    require(centerPositionMs >= 0L) { "Center position must not be negative" }
    require(frameIntervalMs > 0L) { "Frame interval must be positive" }
    require(frameCount > 0 && frameCount % 2 == 1) {
        "Frame count must be a positive odd number"
    }

    val maximumPositionMs = durationMs
        ?.takeIf { it > 0L }
        ?.let { (it - 1L).coerceAtLeast(0L) }
    val safeCenterPositionMs = maximumPositionMs?.let(centerPositionMs::coerceAtMost)
        ?: centerPositionMs
    val middleIndex = frameCount / 2
    return List(frameCount) { index ->
        val offset = (index - middleIndex) * frameIntervalMs
        val position = (safeCenterPositionMs + offset).coerceAtLeast(0L)
        maximumPositionMs?.let(position::coerceAtMost) ?: position
    }
}

private const val DeepMosaicsFrameStride = 3f
private const val DefaultFrameRate = 30f
private const val MinimumSupportedFrameRate = 6f
private const val MaximumSupportedFrameRate = 240f
private const val MinimumFrameIntervalMs = 16L
private const val MaximumFrameIntervalMs = 500L
