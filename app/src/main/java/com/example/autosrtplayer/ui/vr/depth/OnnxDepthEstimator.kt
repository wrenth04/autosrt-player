package com.example.autosrtplayer.ui.vr.depth

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX Runtime implementation of depth estimation.
 * Supports configurable models with explicit tensor contracts.
 */
class OnnxDepthEstimator(
    private val model: DepthModel,
    modelFile: File
) : DepthEstimator {
    private val executor = Executors.newSingleThreadExecutor()
    private val isReleased = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Temporal smoothing
    private var previousDepth: ByteArray? = null
    private val smoothingAlpha = 0.92f // Very high smoothing to reduce jitter

    // Performance tracking
    private val recentInferenceTimes = mutableListOf<Long>()
    private val maxTrackedInferences = 10

    init {
        try {
            // Validate model metadata
            DepthModel.validateModel(model)?.let { error ->
                throw IllegalArgumentException("Invalid model: $error")
            }

            // Initialize ONNX Runtime
            environment = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()

            // Use CPU execution provider for baseline; other EPs can be added later
            session = environment!!.createSession(modelFile.absolutePath, sessionOptions)

            // Validate input/output metadata matches model contract
            validateSessionMetadata(session!!)

            Log.d(TAG, "ONNX depth estimator initialized for model: ${model.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX session", e)
            release()
            throw e
        }
    }

    /**
     * Validates that the ONNX session's input/output metadata matches the model contract.
     */
    private fun validateSessionMetadata(session: OrtSession) {
        val inputInfo = session.inputInfo
        val outputInfo = session.outputInfo

        // Check input tensor exists
        if (!inputInfo.containsKey(model.inputTensorName)) {
            throw IllegalStateException(
                "Model does not have expected input tensor '${model.inputTensorName}'. " +
                "Available: ${inputInfo.keys.joinToString()}"
            )
        }

        // Check output tensor exists
        if (!outputInfo.containsKey(model.outputTensorName)) {
            throw IllegalStateException(
                "Model does not have expected output tensor '${model.outputTensorName}'. " +
                "Available: ${outputInfo.keys.joinToString()}"
            )
        }

        Log.d(TAG, "Session metadata validated: input=${model.inputTensorName}, output=${model.outputTensorName}")
    }

    override fun submitFrame(input: DepthInput, callback: (DepthFrame) -> Unit) {
        if (isReleased.get() || isShuttingDown.get()) {
            Log.w(TAG, "Estimator released or shutting down, ignoring frame submission")
            return
        }

        executor.execute {
            if (isShuttingDown.get()) {
                Log.w(TAG, "Shutdown in progress, skipping inference")
                return@execute
            }

            try {
                val startTime = System.currentTimeMillis()

                // Preprocess input: RGBA -> model-specific format
                val inputTensor = preprocessInput(input)

                // Run inference
                val outputs = session!!.run(mapOf(model.inputTensorName to inputTensor))

                // Extract output tensor
                val outputTensor = outputs[model.outputTensorName] as? OnnxTensor
                    ?: throw IllegalStateException("Output tensor not found or wrong type")

                // Postprocess output: model depth -> normalized 0-255
                val normalizedDepth = postprocessOutput(outputTensor)

                // Apply temporal smoothing
                val smoothedDepth = applyTemporalSmoothing(normalizedDepth)

                val inferenceTime = System.currentTimeMillis() - startTime
                trackInferenceTime(inferenceTime)

                outputTensor.close()
                inputTensor.close()

                if (!isShuttingDown.get()) {
                    val depthFrame = DepthFrame(
                        depthData = smoothedDepth,
                        width = model.inputWidth,
                        height = model.inputHeight,
                        videoPts = input.videoPts,
                        inferenceDurationMs = inferenceTime,
                        status = DepthStatus.Valid
                    )
                    callback(depthFrame)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                if (!isShuttingDown.get()) {
                    val errorFrame = DepthFrame(
                        depthData = ByteArray(model.inputWidth * model.inputHeight),
                        width = model.inputWidth,
                        height = model.inputHeight,
                        videoPts = input.videoPts,
                        inferenceDurationMs = 0,
                        status = DepthStatus.Error
                    )
                    callback(errorFrame)
                }
            }
        }
    }

    /**
     * Convert RGBA ByteBuffer to ONNX tensor with model-specific layout and normalization.
     */
    private fun preprocessInput(input: DepthInput): OnnxTensor {
        val env = environment ?: throw IllegalStateException("Environment is null")

        // Resize and convert RGBA to RGB
        val rgbFloat = resizeAndConvertToRGB(input)

        // Apply model-specific normalization
        val normalized = normalizeInput(rgbFloat)

        // Convert to model's tensor layout
        val tensorData = when (model.inputLayout) {
            TensorLayout.NCHW -> convertToNCHW(normalized, model.inputWidth, model.inputHeight)
            TensorLayout.NHWC -> convertToNHWC(normalized, model.inputWidth, model.inputHeight)
        }

        // Create ONNX tensor
        val shape = when (model.inputLayout) {
            TensorLayout.NCHW -> longArrayOf(1, 3, model.inputHeight.toLong(), model.inputWidth.toLong())
            TensorLayout.NHWC -> longArrayOf(1, model.inputHeight.toLong(), model.inputWidth.toLong(), 3)
        }

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape)
    }

    private fun resizeAndConvertToRGB(input: DepthInput): FloatArray {
        val rgbFloat = FloatArray(model.inputWidth * model.inputHeight * 3)
        input.rgbaData.rewind()
        var outputIndex = 0

        for (y in 0 until model.inputHeight) {
            for (x in 0 until model.inputWidth) {
                // Sample input at scaled coordinates
                val srcX = (x * input.width / model.inputWidth).coerceIn(0, input.width - 1)
                val srcY = (y * input.height / model.inputHeight).coerceIn(0, input.height - 1)
                val srcIndex = (srcY * input.width + srcX) * 4

                val r = input.rgbaData.get(srcIndex).toInt() and 0xFF
                val g = input.rgbaData.get(srcIndex + 1).toInt() and 0xFF
                val b = input.rgbaData.get(srcIndex + 2).toInt() and 0xFF

                // Store as float [0, 255]
                rgbFloat[outputIndex++] = r.toFloat()
                rgbFloat[outputIndex++] = g.toFloat()
                rgbFloat[outputIndex++] = b.toFloat()
            }
        }

        return rgbFloat
    }

    private fun normalizeInput(rgbFloat: FloatArray): FloatArray {
        val normalized = FloatArray(rgbFloat.size)
        val pixelCount = rgbFloat.size / 3

        for (i in 0 until pixelCount) {
            val baseIndex = i * 3
            // Apply (value / 255 - mean) / std
            normalized[baseIndex] = (rgbFloat[baseIndex] / 255f - model.inputNormalizationMean[0]) / model.inputNormalizationStd[0]
            normalized[baseIndex + 1] = (rgbFloat[baseIndex + 1] / 255f - model.inputNormalizationMean[1]) / model.inputNormalizationStd[1]
            normalized[baseIndex + 2] = (rgbFloat[baseIndex + 2] / 255f - model.inputNormalizationMean[2]) / model.inputNormalizationStd[2]
        }

        return normalized
    }

    private fun convertToNCHW(data: FloatArray, @Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int): FloatArray {
        val nchw = FloatArray(data.size)
        val pixelCount = data.size / 3

        for (i in 0 until pixelCount) {
            val srcIndex = i * 3
            nchw[i] = data[srcIndex]                          // R channel
            nchw[pixelCount + i] = data[srcIndex + 1]         // G channel
            nchw[pixelCount * 2 + i] = data[srcIndex + 2]     // B channel
        }

        return nchw
    }

    private fun convertToNHWC(data: FloatArray, @Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int): FloatArray {
        // Data is already in HWC format from resizeAndConvertToRGB
        return data
    }

    /**
     * Convert model output to normalized 0-255 depth map.
     */
    private fun postprocessOutput(outputTensor: OnnxTensor): ByteArray {
        val depthFloat = outputTensor.floatBuffer.array()

        // Check for non-finite values
        if (depthFloat.any { !it.isFinite() }) {
            Log.w(TAG, "Output contains non-finite values, rejecting frame")
            return ByteArray(model.inputWidth * model.inputHeight)
        }

        // Use percentiles to avoid outliers
        val sorted = depthFloat.sorted()
        val p1 = sorted[(sorted.size * 0.01).toInt()]
        val p99 = sorted[(sorted.size * 0.99).toInt()]

        val range = p99 - p1
        if (range < 0.001f) {
            Log.w(TAG, "Flat depth detected, rejecting frame")
            return ByteArray(model.inputWidth * model.inputHeight)
        }

        // Normalize to 0-255
        val normalizedDepth = ByteArray(model.inputWidth * model.inputHeight)
        for (i in depthFloat.indices) {
            val normalized = ((depthFloat[i] - p1) / range).coerceIn(0f, 1f)

            // Apply depth semantics: inverse depth means larger = closer = brighter
            // Our contract is 0=far, 255=near, so inverse depth needs no inversion
            // Regular depth needs inversion
            val finalValue = when (model.depthSemantics) {
                DepthSemantics.INVERSE_DEPTH -> normalized
                DepthSemantics.DEPTH -> 1f - normalized
            }

            normalizedDepth[i] = (finalValue * 255f).toInt().toByte()
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
            val blended = (curr * (1f - smoothingAlpha) + prev * smoothingAlpha).toInt()
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

    override fun getCurrentModel(): DepthModel = model

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
            isShuttingDown.set(true)

            // Shutdown executor and wait for pending work
            executor.shutdown()
            try {
                if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
            }

            // Close ONNX resources
            session?.close()
            session = null
            environment = null
            previousDepth = null

            Log.d(TAG, "ONNX depth estimator released")
        }
    }

    companion object {
        private const val TAG = "OnnxDepthEstimator"
    }
}
