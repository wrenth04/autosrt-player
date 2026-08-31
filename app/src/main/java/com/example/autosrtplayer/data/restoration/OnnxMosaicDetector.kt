package com.example.autosrtplayer.data.restoration

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

data class MosaicDetectionResult(
    val region: DetectedMosaicRegion?,
    val inferenceDurationMs: Long
)

class OnnxMosaicDetector(
    modelFile: File
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val isClosed = AtomicBoolean(false)
    private var session: OrtSession? = null

    val modelInfo: MosaicDetectorModelInfo

    init {
        check(modelFile.isFile) { "馬賽克偵測模型不存在" }
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            session = environment.createSession(modelFile.absolutePath, options)
            modelInfo = inspectSession(requireNotNull(session))
        } catch (error: Exception) {
            try {
                session?.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            } finally {
                session = null
            }
            throw error
        } finally {
            options.close()
        }
    }

    suspend fun detect(
        bitmap: Bitmap,
        threshold: Float
    ): MosaicDetectionResult = withContext(dispatcher) {
        check(!isClosed.get()) { "馬賽克偵測模型已釋放" }
        require(bitmap.width == modelInfo.inputWidth && bitmap.height == modelInfo.inputHeight) {
            "偵測畫面尺寸必須是 ${modelInfo.inputWidth}x${modelInfo.inputHeight}"
        }
        require(threshold in 0f..1f) { "偵測門檻必須介於 0 到 1" }

        val currentSession = requireNotNull(session) { "馬賽克偵測工作階段不存在" }
        val startedAt = System.nanoTime()
        val inputData = bitmapToBgrNchw(bitmap)
        val shape = longArrayOf(
            1,
            3,
            modelInfo.inputHeight.toLong(),
            modelInfo.inputWidth.toLong()
        )

        val region = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputData),
            shape
        ).use { inputTensor ->
            currentSession.run(mapOf(modelInfo.inputTensorName to inputTensor)).use { output ->
                val outputTensor = output[modelInfo.outputTensorName].orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("偵測模型輸出不是 ONNX Tensor")
                regionFromOutput(outputTensor, threshold)
            }
        }

        MosaicDetectionResult(
            region = region,
            inferenceDurationMs = (System.nanoTime() - startedAt) / 1_000_000L
        )
    }

    private fun regionFromOutput(
        outputTensor: OnnxTensor,
        threshold: Float
    ): DetectedMosaicRegion? {
        val shape = outputTensor.info.shape
        if (shape.size != 4 || shape[0] != 1L || shape[1] != 1L) {
            throw IllegalStateException(
                "偵測模型輸出必須是 [1, 1, H, W]，目前為 ${shape.contentToString()}"
            )
        }
        if (shape[2] !in 1L..MaximumOutputDimension.toLong() ||
            shape[3] !in 1L..MaximumOutputDimension.toLong()
        ) {
            throw IllegalStateException(
                "偵測模型輸出尺寸必須介於 1 到 $MaximumOutputDimension"
            )
        }
        val outputHeight = shape[2].toInt()
        val outputWidth = shape[3].toInt()

        val values = outputTensor.floatBuffer
        if (values.remaining() != outputWidth * outputHeight) {
            throw IllegalStateException("偵測模型遮罩長度不符")
        }
        val probabilities = FloatArray(values.remaining())
        values.get(probabilities)
        if (probabilities.any {
                !it.isFinite() || it < -ProbabilityTolerance || it > 1f + ProbabilityTolerance
            }
        ) {
            throw IllegalStateException("偵測模型必須直接輸出 0 到 1 的機率遮罩")
        }
        for (index in probabilities.indices) {
            probabilities[index] = probabilities[index].coerceIn(0f, 1f)
        }

        return findLargestMosaicRegion(
            probabilities = probabilities,
            width = outputWidth,
            height = outputHeight,
            threshold = threshold
        )
    }

    private fun bitmapToBgrNchw(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        return FloatArray(pixelCount * 3).also { output ->
            for (index in pixels.indices) {
                val pixel = pixels[index]
                output[index] = (pixel and 0xff) / 255f
                output[pixelCount + index] = ((pixel ushr 8) and 0xff) / 255f
                output[pixelCount * 2 + index] = ((pixel ushr 16) and 0xff) / 255f
            }
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        executor.execute {
            try {
                session?.close()
            } finally {
                session = null
                dispatcher.close()
            }
        }
    }

    companion object {
        private const val DefaultDynamicInputSize = 360
        private const val MinimumInputSize = 64
        private const val MaximumInputSize = 720
        private const val MaximumOutputDimension = 1440
        private const val ProbabilityTolerance = 0.00001f

        fun inspect(modelFile: File): MosaicDetectorModelInfo {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                environment.createSession(modelFile.absolutePath, options).use { session ->
                    return inspectSession(session)
                }
            } finally {
                options.close()
            }
        }

        private fun inspectSession(session: OrtSession): MosaicDetectorModelInfo {
            if (session.inputInfo.size != 1 || session.outputInfo.size != 1) {
                throw IllegalStateException("偵測模型必須各有一個輸入與輸出")
            }
            val input = session.inputInfo.entries.single()
            val output = session.outputInfo.entries.single()
            val inputInfo = input.value.requireFloatTensor(input.key)
            val outputInfo = output.value.requireFloatTensor(output.key)
            val inputShape = inputInfo.shape
            val outputShape = outputInfo.shape

            if (inputShape.size != 4 ||
                (inputShape[0] > 0L && inputShape[0] != 1L) ||
                inputShape[1] != 3L
            ) {
                throw IllegalStateException("偵測模型輸入必須是 [1, 3, H, W]")
            }
            if (outputShape.size != 4 ||
                (outputShape[0] > 0L && outputShape[0] != 1L) ||
                outputShape[1] != 1L
            ) {
                throw IllegalStateException("偵測模型輸出必須是 [1, 1, H, W]")
            }
            listOf(outputShape[2], outputShape[3])
                .filter { it > 0L }
                .forEach { dimension ->
                    if (dimension > MaximumOutputDimension.toLong()) {
                        throw IllegalStateException(
                            "偵測模型輸出尺寸不得超過 $MaximumOutputDimension"
                        )
                    }
                }

            val inputHeight = inputShape[2].resolveInputDimension()
            val inputWidth = inputShape[3].resolveInputDimension()
            if (inputWidth !in MinimumInputSize..MaximumInputSize ||
                inputHeight !in MinimumInputSize..MaximumInputSize
            ) {
                throw IllegalStateException(
                    "偵測模型輸入尺寸必須介於 $MinimumInputSize 到 $MaximumInputSize"
                )
            }

            return MosaicDetectorModelInfo(
                inputTensorName = input.key,
                outputTensorName = output.key,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                outputWidth = outputShape[3].takeIf { it > 0L }?.toInt(),
                outputHeight = outputShape[2].takeIf { it > 0L }?.toInt()
            )
        }

        private fun NodeInfo.requireFloatTensor(name: String): TensorInfo {
            val tensorInfo = info as? TensorInfo
                ?: throw IllegalStateException("模型節點 '$name' 不是 Tensor")
            if (tensorInfo.type != OnnxJavaType.FLOAT) {
                throw IllegalStateException("模型節點 '$name' 必須使用 float32")
            }
            return tensorInfo
        }

        private fun Long.resolveInputDimension(): Int {
            return if (this > 0L) toInt() else DefaultDynamicInputSize
        }
    }
}
