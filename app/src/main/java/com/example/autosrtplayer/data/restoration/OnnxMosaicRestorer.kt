package com.example.autosrtplayer.data.restoration

import android.graphics.Bitmap
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

data class RestoredImage(
    val bitmap: Bitmap,
    val inferenceDurationMs: Long
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

    init {
        RestorationModel.validateModel(model)?.let { error ->
            throw IllegalArgumentException("Invalid restoration model: $error")
        }
        check(modelFile.isFile) { "Restoration model file is unavailable" }

        val options = OrtSession.SessionOptions()
        try {
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
            }
            throw error
        } finally {
            options.close()
        }
    }

    suspend fun restore(input: Bitmap): RestoredImage = withContext(dispatcher) {
        check(!isClosed.get()) { "Restoration model has been released" }
        require(input.width > 0 && input.height > 0) { "Input bitmap is empty" }

        val currentSession = requireNotNull(session) { "Restoration session is unavailable" }
        val startedAt = System.nanoTime()
        val inputData = bitmapToNchw(input)
        val inputShape = longArrayOf(1, 3, input.height.toLong(), input.width.toLong())

        val outputBitmap = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputData),
            inputShape
        ).use { inputTensor ->
            currentSession.run(mapOf(model.inputTensorName to inputTensor)).use { output ->
                val outputTensor = output[model.outputTensorName].orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException(
                        "Model output '${model.outputTensorName}' is missing or is not a tensor"
                    )
                tensorToBitmap(
                    tensor = outputTensor,
                    expectedWidth = input.width * model.outputScale,
                    expectedHeight = input.height * model.outputScale
                )
            }
        }

        RestoredImage(
            bitmap = outputBitmap,
            inferenceDurationMs = (System.nanoTime() - startedAt) / 1_000_000L
        )
    }

    private fun validateTensorContract(session: OrtSession) {
        validateTensor(
            nodeInfo = session.inputInfo[model.inputTensorName],
            tensorName = model.inputTensorName,
            isInput = true
        )
        validateTensor(
            nodeInfo = session.outputInfo[model.outputTensorName],
            tensorName = model.outputTensorName,
            isInput = false
        )
    }

    private fun validateTensor(nodeInfo: NodeInfo?, tensorName: String, isInput: Boolean) {
        val tensorInfo = nodeInfo?.info as? TensorInfo
            ?: throw IllegalStateException("Model tensor '$tensorName' is missing")
        if (tensorInfo.type != OnnxJavaType.FLOAT) {
            throw IllegalStateException("Model tensor '$tensorName' must use float32")
        }
        val shape = tensorInfo.shape
        if (shape.size != 4 || (shape[1] > 0L && shape[1] != 3L)) {
            val direction = if (isInput) "input" else "output"
            throw IllegalStateException("Model $direction '$tensorName' must be NCHW RGB")
        }
    }

    private fun bitmapToNchw(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        return FloatArray(pixelCount * 3).also { output ->
            for (index in pixels.indices) {
                val pixel = pixels[index]
                output[index] = ((pixel ushr 16) and 0xff) / 255f
                output[pixelCount + index] = ((pixel ushr 8) and 0xff) / 255f
                output[pixelCount * 2 + index] = (pixel and 0xff) / 255f
            }
        }
    }

    private fun tensorToBitmap(
        tensor: OnnxTensor,
        expectedWidth: Int,
        expectedHeight: Int
    ): Bitmap {
        val shape = tensor.info.shape
        if (shape.size != 4 ||
            shape[0] != 1L ||
            shape[1] != 3L ||
            shape[2] != expectedHeight.toLong() ||
            shape[3] != expectedWidth.toLong()
        ) {
            throw IllegalStateException(
                "Unexpected model output shape: ${shape.joinToString(prefix = "[", postfix = "]")}"
            )
        }

        val pixelCount = expectedWidth * expectedHeight
        val values = tensor.floatBuffer
        if (values.remaining() != pixelCount * 3) {
            throw IllegalStateException("Unexpected model output length: ${values.remaining()}")
        }

        val pixels = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val red = (values.get(index).coerceIn(0f, 1f) * 255f).toInt()
            val green = (values.get(pixelCount + index).coerceIn(0f, 1f) * 255f).toInt()
            val blue = (values.get(pixelCount * 2 + index).coerceIn(0f, 1f) * 255f).toInt()
            pixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }

        return Bitmap.createBitmap(
            pixels,
            expectedWidth,
            expectedHeight,
            Bitmap.Config.ARGB_8888
        )
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
}
