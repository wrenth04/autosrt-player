package com.example.autosrtplayer.ui.vr.depth

import java.nio.ByteBuffer

/**
 * Immutable result from depth inference containing normalized depth map and metadata.
 */
data class DepthFrame(
    /** Normalized 8-bit depth map (0=far, 255=near), row-major format */
    val depthData: ByteArray,
    /** Width of the depth map */
    val width: Int,
    /** Height of the depth map */
    val height: Int,
    /** Video presentation timestamp this depth corresponds to (microseconds) */
    val videoPts: Long,
    /** Inference duration in milliseconds */
    val inferenceDurationMs: Long,
    /** Status of this depth frame */
    val status: DepthStatus
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DepthFrame

        if (!depthData.contentEquals(other.depthData)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (videoPts != other.videoPts) return false

        return true
    }

    override fun hashCode(): Int {
        var result = depthData.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + videoPts.hashCode()
        return result
    }
}

/**
 * Status of a depth frame inference.
 */
enum class DepthStatus {
    /** Inference succeeded and depth is valid */
    Valid,
    /** Inference completed but result was rejected (flat, non-finite, etc.) */
    Rejected,
    /** Inference failed due to error */
    Error,
    /** Inference timed out */
    Timeout
}

/**
 * Input frame for depth inference.
 */
data class DepthInput(
    /** RGBA pixel data in row-major format */
    val rgbaData: ByteBuffer,
    /** Width of the input frame */
    val width: Int,
    /** Height of the input frame */
    val height: Int,
    /** Video presentation timestamp (microseconds) */
    val videoPts: Long
)

/**
 * Interface for depth estimation from video frames.
 * Implementations must be thread-safe and lifecycle-aware.
 */
interface DepthEstimator {
    /**
     * Submit a frame for depth inference.
     * This is non-blocking and returns immediately.
     * The result will be delivered via the callback.
     *
     * @param input The input frame to process
     * @param callback Invoked on completion with the depth result
     */
    fun submitFrame(input: DepthInput, callback: (DepthFrame) -> Unit)

    /**
     * Returns the current model being used, or null if no model is loaded.
     */
    fun getCurrentModel(): DepthModel?

    /**
     * Returns the average inference time in milliseconds over recent frames.
     */
    fun getAverageInferenceMs(): Float

    /**
     * Release all resources. The estimator cannot be used after this call.
     */
    fun release()
}
