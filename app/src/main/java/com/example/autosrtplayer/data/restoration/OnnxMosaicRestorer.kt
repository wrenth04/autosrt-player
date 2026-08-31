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
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

data class RestoredImage(
    val bitmap: Bitmap,
    val inferenceDurationMs: Long,
    val changeFraction: Float
)

class OnnxMosaicRestorer(
    private val model: RestorationModel,
    modelFile: File
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val isClosed = AtomicBoolean(false)
    private var session: OrtSession? = null
    private var previousOutput: FloatArray? = null
    private var sessionPermitHeld = false

    init {
        RestorationModel.validateModel(model)?.let { error ->
            throw IllegalArgumentException("Invalid restoration model: $error")
        }
        check(modelFile.isFile) { "DeepMosaics 模型不存在" }

        val options = OrtSession.SessionOptions()
        try {
            SessionPermit.acquireUninterruptibly()
            sessionPermitHeld = true
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            session = environment.createSession(modelFile.absolutePath, options)
            validateTensorContract(requireNotNull(session))
        } catch (error: Exception) {
            try {
                session?.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            } finally {
                session = null
                releaseSessionPermit()
            }
            throw error
        } finally {
            options.close()
        }
    }

    suspend fun restore(
        frames: List<Bitmap>,
        alphaMask: FloatArray?
    ): RestoredImage {
        val inference = withContext(dispatcher) {
            check(!isClosed.get()) { "DeepMosaics 模型已釋放" }
            require(frames.size == model.temporalFrameCount) {
                "DeepMosaics 需要 ${model.temporalFrameCount} 張時序影格"
            }
            frames.forEach { frame ->
                require(frame.width == model.inputSize && frame.height == model.inputSize) {
                    "DeepMosaics 影格必須是 ${model.inputSize}x${model.inputSize}"
                }
            }

            val currentSession = requireNotNull(session) { "DeepMosaics 工作階段不存在" }
            val startedAt = System.nanoTime()
            val normalizedFrames = frames.map(::bitmapToNormalizedRgb)
            val pixelCount = model.inputSize * model.inputSize
            val stream = buildDeepMosaicsTemporalStream(normalizedFrames, pixelCount)
            val previous = previousOutput ?: normalizedFrames[model.temporalFrameCount / 2]
            val streamShape = longArrayOf(
                1,
                3,
                model.temporalFrameCount.toLong(),
                model.inputSize.toLong(),
                model.inputSize.toLong()
            )
            val previousShape = longArrayOf(
                1,
                3,
                model.inputSize.toLong(),
                model.inputSize.toLong()
            )

            val outputValues = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(stream),
                streamShape
            ).use { streamTensor ->
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(previous),
                    previousShape
                ).use { previousTensor ->
                    currentSession.run(
                        mapOf(
                            model.inputTensorName to streamTensor,
                            model.previousInputTensorName to previousTensor
                        )
                    ).use { output ->
                        val outputTensor =
                            output[model.outputTensorName].orElse(null) as? OnnxTensor
                                ?: throw IllegalStateException(
                                    "DeepMosaics 輸出 '${model.outputTensorName}' 不存在"
                                )
                        readOutput(outputTensor)
                    }
                }
            }

            previousOutput = outputValues.copyOf()
            DeepMosaicsInference(
                pixels = normalizedRgbToFeatheredArgb(
                    values = outputValues,
                    width = model.inputSize,
                    height = model.inputSize,
                    alphaMask = alphaMask
                ),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000L,
                changeFraction = calculateNormalizedRgbChangeFraction(
                    original = normalizedFrames[model.temporalFrameCount / 2],
                    restored = outputValues,
                    alphaMask = alphaMask
                )
            )
        }

        return RestoredImage(
            bitmap = Bitmap.createBitmap(
                inference.pixels,
                model.inputSize,
                model.inputSize,
                Bitmap.Config.ARGB_8888
            ),
            inferenceDurationMs = inference.durationMs,
            changeFraction = inference.changeFraction
        )
    }

    suspend fun reset() = withContext(dispatcher) {
        previousOutput = null
    }

    private fun bitmapToNormalizedRgb(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return argbToNormalizedRgbNchw(pixels)
    }

    private fun readOutput(outputTensor: OnnxTensor): FloatArray {
        val shape = outputTensor.info.shape
        val expectedSize = model.inputSize.toLong()
        if (shape.size != 4 ||
            shape[0] != 1L ||
            shape[1] != 3L ||
            shape[2] != expectedSize ||
            shape[3] != expectedSize
        ) {
            throw IllegalStateException(
                "DeepMosaics 輸出必須是 [1, 3, ${model.inputSize}, ${model.inputSize}]，" +
                    "目前為 ${shape.contentToString()}"
            )
        }

        val expectedValues = model.inputSize * model.inputSize * 3
        val buffer = outputTensor.floatBuffer
        if (buffer.remaining() != expectedValues) {
            throw IllegalStateException("DeepMosaics 輸出長度不符")
        }
        return FloatArray(expectedValues).also { values ->
            buffer.get(values)
            if (values.any { !it.isFinite() }) {
                throw IllegalStateException("DeepMosaics 輸出包含無效數值")
            }
            for (index in values.indices) {
                values[index] = values[index].coerceIn(-1f, 1f)
            }
        }
    }

    private fun validateTensorContract(currentSession: OrtSession) {
        if (currentSession.inputInfo.size != 2 || currentSession.outputInfo.size != 1) {
            throw IllegalStateException("DeepMosaics 必須有兩個輸入與一個輸出")
        }
        currentSession.inputInfo[model.inputTensorName].requireFloatTensor(
            name = model.inputTensorName,
            expectedShape = longArrayOf(
                1,
                3,
                model.temporalFrameCount.toLong(),
                model.inputSize.toLong(),
                model.inputSize.toLong()
            )
        )
        currentSession.inputInfo[model.previousInputTensorName].requireFloatTensor(
            name = model.previousInputTensorName,
            expectedShape = longArrayOf(
                1,
                3,
                model.inputSize.toLong(),
                model.inputSize.toLong()
            )
        )
        val outputInfo = currentSession.outputInfo[model.outputTensorName]
            ?.info as? TensorInfo
            ?: throw IllegalStateException("DeepMosaics 輸出 '${model.outputTensorName}' 不存在")
        if (outputInfo.type != OnnxJavaType.FLOAT) {
            throw IllegalStateException("DeepMosaics 輸出必須使用 float32")
        }
        val outputShape = outputInfo.shape
        if (outputShape.size != 4 ||
            (outputShape[0] > 0L && outputShape[0] != 1L) ||
            outputShape[1] != 3L ||
            (outputShape[2] > 0L && outputShape[2] != model.inputSize.toLong()) ||
            (outputShape[3] > 0L && outputShape[3] != model.inputSize.toLong())
        ) {
            throw IllegalStateException(
                "DeepMosaics 輸出必須是 [1, 3, ${model.inputSize}, ${model.inputSize}]"
            )
        }
    }

    private fun NodeInfo?.requireFloatTensor(
        name: String,
        expectedShape: LongArray
    ) {
        val tensorInfo = this?.info as? TensorInfo
            ?: throw IllegalStateException("DeepMosaics 輸入 '$name' 不存在")
        if (tensorInfo.type != OnnxJavaType.FLOAT) {
            throw IllegalStateException("DeepMosaics 輸入 '$name' 必須使用 float32")
        }
        if (!tensorInfo.shape.contentEquals(expectedShape)) {
            throw IllegalStateException(
                "DeepMosaics 輸入 '$name' 必須是 ${expectedShape.contentToString()}"
            )
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        executor.execute {
            try {
                previousOutput = null
                session?.close()
            } finally {
                session = null
                releaseSessionPermit()
                dispatcher.close()
            }
        }
    }

    private fun releaseSessionPermit() {
        if (sessionPermitHeld) {
            sessionPermitHeld = false
            SessionPermit.release()
        }
    }

    private data class DeepMosaicsInference(
        val pixels: IntArray,
        val durationMs: Long,
        val changeFraction: Float
    )

    companion object {
        private val SessionPermit = Semaphore(1, true)
    }
}
