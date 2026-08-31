package com.example.autosrtplayer.ui.restoration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicRestorationFeedbackTest {
    @Test
    fun `active inference takes priority over other feedback`() {
        val feedback = resolveMosaicRestorationFeedback(
            pendingFeedback = MosaicRestorationFeedback.NoMosaicDetected,
            isRestorerLoading = true,
            isDetectorLoading = true,
            isDetecting = true,
            isCapturingFrames = true,
            capturedFrameCount = 3,
            totalFrameCount = 5,
            isRestoring = true
        )

        assertSame(MosaicRestorationFeedback.Restoring, feedback)
        assertTrue(requireNotNull(feedback).isBusy)
    }

    @Test
    fun `model loading remains visible as processing`() {
        val feedback = resolveMosaicRestorationFeedback(
            pendingFeedback = MosaicRestorationFeedback.Preparing,
            isRestorerLoading = true,
            isDetectorLoading = false,
            isDetecting = false,
            isCapturingFrames = false,
            capturedFrameCount = 0,
            totalFrameCount = 5,
            isRestoring = false
        )

        assertSame(MosaicRestorationFeedback.LoadingModels, feedback)
        assertTrue(requireNotNull(feedback).isBusy)
        assertTrue(feedback.displayMessage().contains("正在載入"))
    }

    @Test
    fun `temporal capture is reported before inference`() {
        val feedback = resolveMosaicRestorationFeedback(
            pendingFeedback = MosaicRestorationFeedback.Preparing,
            isRestorerLoading = false,
            isDetectorLoading = false,
            isDetecting = false,
            isCapturingFrames = true,
            capturedFrameCount = 3,
            totalFrameCount = 5,
            isRestoring = false
        )

        assertEquals(
            MosaicRestorationFeedback.CapturingFrames(3, 5),
            feedback
        )
        assertTrue(requireNotNull(feedback).displayMessage().contains("前後"))
        assertEquals(0.44f, feedback.progressFraction ?: 0f, 0.001f)
    }

    @Test
    fun `completed feedback reports inference duration`() {
        val completed = MosaicRestorationFeedback.Completed(
            inferenceDurationMs = 3_120L,
            visibleChangeFraction = 0.042f
        )

        assertFalse(completed.isBusy)
        assertTrue(completed.isTemporaryResult)
        assertEquals(
            "DeepMosaics 處理完成（3.1 秒，模型變化 4.20%）",
            completed.displayMessage()
        )
    }

    @Test
    fun `completed feedback warns when model output barely changes`() {
        val completed = MosaicRestorationFeedback.Completed(
            inferenceDurationMs = 900L,
            visibleChangeFraction = 0.0012f
        )

        assertFalse(completed.hasVisibleChange)
        assertTrue(completed.displayMessage().contains("幾乎相同"))
        assertTrue(completed.displayMessage().contains("0.12%"))
    }

    @Test
    fun `missing detection explains recovery options`() {
        val feedback = MosaicRestorationFeedback.NoMosaicDetected

        assertFalse(feedback.isBusy)
        assertTrue(feedback.isTemporaryResult)
        assertTrue(feedback.displayMessage().contains("降低偵測門檻"))
        assertTrue(feedback.displayMessage().contains("手動框選"))
    }
}
