package com.example.autosrtplayer.ui.restoration

import java.util.Locale

internal sealed interface MosaicRestorationFeedback {
    data object Preparing : MosaicRestorationFeedback
    data object LoadingModels : MosaicRestorationFeedback
    data object Detecting : MosaicRestorationFeedback
    data class CapturingFrames(
        val capturedFrameCount: Int,
        val totalFrameCount: Int
    ) : MosaicRestorationFeedback {
        init {
            require(totalFrameCount > 0) { "Total frame count must be positive" }
            require(capturedFrameCount in 0..totalFrameCount) {
                "Captured frame count must be within the total"
            }
        }
    }
    data object Restoring : MosaicRestorationFeedback
    data class Completed(
        val inferenceDurationMs: Long,
        val modelChangeFraction: Float,
        val strength: Float
    ) : MosaicRestorationFeedback
    data object NoMosaicDetected : MosaicRestorationFeedback
}

internal fun resolveMosaicRestorationFeedback(
    pendingFeedback: MosaicRestorationFeedback?,
    isRestorerLoading: Boolean,
    isDetectorLoading: Boolean,
    isDetecting: Boolean,
    isCapturingFrames: Boolean,
    capturedFrameCount: Int,
    totalFrameCount: Int,
    isRestoring: Boolean
): MosaicRestorationFeedback? {
    return when {
        isRestoring -> MosaicRestorationFeedback.Restoring
        isCapturingFrames -> MosaicRestorationFeedback.CapturingFrames(
            capturedFrameCount = capturedFrameCount.coerceIn(
                0,
                totalFrameCount.coerceAtLeast(1)
            ),
            totalFrameCount = totalFrameCount.coerceAtLeast(1)
        )
        isDetecting -> MosaicRestorationFeedback.Detecting
        isRestorerLoading || isDetectorLoading -> MosaicRestorationFeedback.LoadingModels
        else -> pendingFeedback
    }
}

internal val MosaicRestorationFeedback.isBusy: Boolean
    get() = when (this) {
        MosaicRestorationFeedback.Preparing,
        MosaicRestorationFeedback.LoadingModels,
        MosaicRestorationFeedback.Detecting,
        is MosaicRestorationFeedback.CapturingFrames,
        MosaicRestorationFeedback.Restoring -> true
        is MosaicRestorationFeedback.Completed,
        MosaicRestorationFeedback.NoMosaicDetected -> false
    }

internal val MosaicRestorationFeedback.isTemporaryResult: Boolean
    get() = this is MosaicRestorationFeedback.Completed ||
        this is MosaicRestorationFeedback.NoMosaicDetected

internal fun MosaicRestorationFeedback.displayMessage(): String {
    return when (this) {
        MosaicRestorationFeedback.Preparing -> "正在準備目前畫面…"
        MosaicRestorationFeedback.LoadingModels ->
            "正在載入 DeepMosaics 模型，首次處理會較久…"
        MosaicRestorationFeedback.Detecting -> "正在自動偵測馬賽克範圍…"
        is MosaicRestorationFeedback.CapturingFrames ->
            "正在擷取目前畫面前後的時序影格 " +
                "$capturedFrameCount/$totalFrameCount"
        MosaicRestorationFeedback.Restoring -> "正在使用 DeepMosaics 處理目前畫面…"
        is MosaicRestorationFeedback.Completed -> {
            val change = formatMosaicChangeFraction(modelChangeFraction)
            if (hasVisibleChange) {
                "DeepMosaics 處理完成（${formatMosaicInferenceDuration(inferenceDurationMs)}，" +
                    "模型變化 $change，混合強度 ${formatMosaicStrength(strength)}）"
            } else {
                "推論完成，但模型輸出與原畫面幾乎相同（變化 $change）"
            }
        }
        MosaicRestorationFeedback.NoMosaicDetected ->
            "未偵測到馬賽克；請降低偵測門檻，或改用手動框選"
    }
}

internal val MosaicRestorationFeedback.Completed.hasVisibleChange: Boolean
    get() = modelChangeFraction >= MinimumVisibleChangeFraction

internal val MosaicRestorationFeedback.progressFraction: Float?
    get() = when (this) {
        MosaicRestorationFeedback.Preparing -> 0.05f
        MosaicRestorationFeedback.LoadingModels -> null
        MosaicRestorationFeedback.Detecting -> 0.15f
        is MosaicRestorationFeedback.CapturingFrames -> {
            0.2f + 0.4f * capturedFrameCount / totalFrameCount
        }
        MosaicRestorationFeedback.Restoring -> 0.75f
        is MosaicRestorationFeedback.Completed -> 1f
        MosaicRestorationFeedback.NoMosaicDetected -> null
    }

internal fun formatMosaicInferenceDuration(durationMs: Long): String {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    return if (safeDurationMs < 1_000L) {
        "$safeDurationMs 毫秒"
    } else {
        String.format(Locale.ROOT, "%.1f 秒", safeDurationMs / 1_000.0)
    }
}

internal fun formatMosaicChangeFraction(changeFraction: Float): String {
    val safeChangeFraction = changeFraction
        .takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
        ?: 0f
    return String.format(Locale.ROOT, "%.2f%%", safeChangeFraction * 100f)
}

internal fun formatMosaicStrength(strength: Float): String {
    return "${(strength.coerceIn(0f, 1f) * 100f).toInt()}%"
}

private const val MinimumVisibleChangeFraction = 0.005f
