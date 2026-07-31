package com.example.autosrtplayer.ui.vr.depth

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * TensorFlow Lite implementation of depth estimation.
 * Uses MiDaS Small model optimized for mobile.
 */
class TFLiteDepthEstimator(
    context: Context,
    modelFile: File
) : DepthEstimator {
    private val executor = Executors.newSingleThreadExecutor()
    private val isReleased = AtomicBoolean(false)
    private val currentModel = AtomicReference<DepthModel>(null)

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Input/output buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null

    // Model configuration (MiDaS Small: 256x256 RGB, float32)
    private val inputWidth = 256
    private val inputHeight = 256
    private val inputChannels = 3
    private val pixelSize = 4 // float32

    // Temporal smoothing
    private var previousDepth: ByteArray? = null
    private val smoothingAlpha = 0.85f // Higher = more responsive, lower = smoother

    // Performance tracking
    private val recentInferenceTimes = mutableListOf<Long>()
    private val maxTrackedInferences = 10

    init {
        try {
            // Initialize interpreter with GPU acceleration if available
            val options = Interpreter.Options()

            val compatibilityList = CompatibilityList()
            if (compatibilityList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU delegate enabled for depth estimation")
            } else {
                Log.w(TAG, "GPU delegate not available, using CPU")
                options.setNumThreads(4)
            }

            interpreter = Interpreter(modelFile, options)

            // Allocate input/output buffers
            val inputSize = inputWidth * inputHeight * inputChannels * pixelSize
            inputBuffer = ByteBuffer.allocateDirect(inputSize).apply {
                order(ByteOrder.nativeOrder())
            }

            val outputSize = inputWidth * inputHeight * pixelSize
            outputBuffer = ByteBuffer.allocateDirect(outputSize).apply {
                order(ByteOrder.nativeOrder())
            }

            Log.d(TAG, "TFLite depth estimator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite interpreter", e)
            release()
            throw e
        }
    }

    override fun submitFrame(input: DepthInput, callback: (DepthFrame) -> Unit) {
        if (isReleased.get()) {
            Log.w(TAG, "Estimator released, ignoring frame submission")
            return
        }

        executor.execute {
            try {
                val startTime = System.currentTimeMillis()

                // Preprocess input: RGBA -> RGB float32
                val rgbFloat = preprocessInput(input)

                // Run inference
                inputBuffer?.rewind()
                inputBuffer?.asFloatBuffer()?.put(rgbFloat)

                outputBuffer?.rewind()
                interpreter?.run(inputBuffer, outputBuffer)

                // Postprocess output: relative depth -> normalized 0-255
                val normalizedDepth = postprocessOutput(outputBuffer!!)

                // Apply temporal smoothing
                val smoothedDepth = applyTemporalSmoothing(normalizedDepth)

                val inferenceTime = System.currentTimeMillis() - startTime
                trackInferenceTime(inferenceTime)

                val depthFrame = DepthFrame(
                    depthData = smoothedDepth,
                    width = inputWidth,
                    height = inputHeight,
                    videoPts = input.videoPts,
                    inferenceDurationMs = inferenceTime,
                    status = DepthStatus.Valid
                )

                callback(depthFrame)
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                val errorFrame = DepthFrame(
                    depthData = ByteArray(inputWidth * inputHeight),
                    width = inputWidth,
                    height = inputHeight,
                    videoPts = input.videoPts,
                    inferenceDurationMs = 0,
                    status = DepthStatus.Error
                )
                callback(errorFrame)
            }
        }
    }

    /**
     * Convert RGBA ByteBuffer to RGB float32 array with normalization.
     * MiDaS expects RGB input normalized to [0, 1].
     */
    private fun preprocessInput(input: DepthInput): FloatArray {
        val rgbFloat = FloatArray(inputWidth * inputHeight * inputChannels)

        input.rgbaData.rewind()
        var outputIndex = 0

        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                // Sample input at scaled coordinates
                val srcX = (x * input.width / inputWidth).coerceIn(0, input.width - 1)
                val srcY = (y * input.height / inputHeight).coerceIn(0, input.height - 1)
                val srcIndex = (srcY * input.width + srcX) * 4

                val r = input.rgbaData.get(srcIndex).toInt() and 0xFF
                val g = input.rgbaData.get(srcIndex + 1).toInt() and 0xFF
                val b = input.rgbaData.get(srcIndex + 2).toInt() and 0xFF

                // Normalize to [0, 1]
                rgbFloat[outputIndex++] = r / 255f
                rgbFloat[outputIndex++] = g / 255f
                rgbFloat[outputIndex++] = b / 255f
            }
        }

        return rgbFloat
    }

    /**
     * Convert relative inverse depth to normalized 0-255 depth map.
     * MiDaS outputs relative depth where larger values = closer objects.
     */
    private fun postprocessOutput(output: ByteBuffer): ByteArray {
        output.rewind()
        val depthFloat = FloatArray(inputWidth * inputHeight)
        output.asFloatBuffer().get(depthFloat)

        // Find min/max for normalization (use percentiles to avoid outliers)
        val sorted = depthFloat.sorted()
        val p1 = sorted[(sorted.size * 0.01).toInt()]
        val p99 = sorted[(sorted.size * 0.99).toInt()]

        val range = p99 - p1
        if (range < 0.001f) {
            // Flat depth, reject
            return ByteArray(inputWidth * inputHeight)
        }

        // Normalize to 0-255 (inverted: closer = brighter)
        val normalizedDepth = ByteArray(inputWidth * inputHeight)
        for (i in depthFloat.indices) {
            val normalized = ((depthFloat[i] - p1) / range).coerceIn(0f, 1f)
            normalizedDepth[i] = (normalized * 255f).toInt().toByte()
        }

        return normalizedDepth
    }

    /**
     * Apply exponential moving average for temporal stability.
     */
    private fun applyTemporalSmoothing(currentDepth: ByteArray): ByteArray {
        val previous = previousDepth

        if (previous == null || previous.size != currentDepth.size) {
            previousDepth = currentDepth.copyOf()
            return currentDepth
        }

        val smoothed = ByteArray(currentDepth.size)
        for (i in currentDepth.indices) {
            val curr = currentDepth[i].toInt() and 0xFF
            val prev = previous[i].toInt() and 0xFF
            val blended = (curr * smoothingAlpha + prev * (1f - smoothingAlpha)).toInt()
            smoothed[i] = blended.coerceIn(0, 255).toByte()
        }

        previousDepth = smoothed.copyOf()
        return smoothed
    }

    private fun trackInferenceTime(timeMs: Long) {
        synchronized(recentInferenceTimes) {
            recentInferenceTimes.add(timeMs)
            if (recentInferenceTimes.size > maxTrackedInferences) {
                recentInferenceTimes.removeAt(0)
            }
        }
    }

    override fun getCurrentModel(): DepthModel? = currentModel.get()

    override fun getAverageInferenceMs(): Float {
        synchronized(recentInferenceTimes) {
            return if (recentInferenceTimes.isEmpty()) {
                0f
            } else {
                recentInferenceTimes.average().toFloat()
            }
        }
    }

    override fun release() {
        if (isReleased.compareAndSet(false, true)) {
            executor.shutdown()
            interpreter?.close()
            gpuDelegate?.close()
            interpreter = null
            gpuDelegate = null
            inputBuffer = null
            outputBuffer = null
            previousDepth = null
            Log.d(TAG, "TFLite depth estimator released")
        }
    }

    companion object {
        private const val TAG = "TFLiteDepthEstimator"
    }
}
