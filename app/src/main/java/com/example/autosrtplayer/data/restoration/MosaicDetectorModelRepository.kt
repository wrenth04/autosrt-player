package com.example.autosrtplayer.data.restoration

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MosaicDetectorModelRepository(
    context: Context,
    private val httpClient: OkHttpClient
) {
    private val modelDirectory = File(context.filesDir, "restoration_models")
    private val modelFile = File(modelDirectory, ModelFileName)
    private val temporaryFile = File(modelDirectory, "$ModelFileName.part")
    private val operationMutex = Mutex()
    private val _status = MutableStateFlow<MosaicDetectorModelStatus>(
        MosaicDetectorModelStatus.NotConfigured
    )

    val status: StateFlow<MosaicDetectorModelStatus> = _status

    private var validatedSha256: String? = null
    private var validatedInfo: MosaicDetectorModelInfo? = null

    init {
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            Log.e(Tag, "Could not create detector model directory")
        }
    }

    fun getModelFile(spec: MosaicDetectorModelSpec): File? {
        val normalized = spec.normalized()
        return if (normalized.sha256 == validatedSha256 && modelFile.isFile) {
            modelFile
        } else {
            null
        }
    }

    fun updateConfiguration(spec: MosaicDetectorModelSpec) {
        val normalized = spec.normalized()
        _status.value = when {
            normalized.validationError() != null -> MosaicDetectorModelStatus.NotConfigured
            normalized.sha256 == validatedSha256 && modelFile.isFile -> {
                val info = validatedInfo
                if (info != null) {
                    MosaicDetectorModelStatus.Ready(info)
                } else {
                    MosaicDetectorModelStatus.Verifying
                }
            }
            modelFile.isFile -> MosaicDetectorModelStatus.Verifying
            else -> MosaicDetectorModelStatus.NotDownloaded
        }
    }

    suspend fun refresh(spec: MosaicDetectorModelSpec) = operationMutex.withLock {
        val normalized = spec.normalized()
        val validationError = normalized.validationError()
        if (validationError != null) {
            _status.value = MosaicDetectorModelStatus.NotConfigured
            return@withLock
        }
        if (!modelFile.isFile) {
            clearValidatedModel()
            _status.value = MosaicDetectorModelStatus.NotDownloaded
            return@withLock
        }
        if (modelFile.length() > MaximumModelBytes) {
            clearValidatedModel()
            _status.value = MosaicDetectorModelStatus.Error(
                "偵測模型超過 ${MaximumModelBytes / 1024 / 1024} MB 上限"
            )
            return@withLock
        }

        _status.value = MosaicDetectorModelStatus.Verifying
        withContext(Dispatchers.IO) {
            try {
                validateFile(normalized)
            } catch (error: IOException) {
                clearValidatedModel()
                _status.value = MosaicDetectorModelStatus.Error(
                    error.message ?: "偵測模型驗證失敗"
                )
            } catch (error: OrtException) {
                clearValidatedModel()
                _status.value = MosaicDetectorModelStatus.Error(
                    error.message ?: "偵測模型不是相容的 ONNX 模型"
                )
            } catch (error: RuntimeException) {
                clearValidatedModel()
                _status.value = MosaicDetectorModelStatus.Error(
                    error.message ?: "偵測模型格式錯誤"
                )
            }
        }
    }

    suspend fun download(spec: MosaicDetectorModelSpec): Result<File> = operationMutex.withLock {
        val normalized = spec.normalized()
        normalized.validationError()?.let { error ->
            _status.value = MosaicDetectorModelStatus.Error(error)
            return@withLock Result.failure(IllegalArgumentException(error))
        }

        withContext(Dispatchers.IO) {
            try {
                if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                    throw IOException("無法建立偵測模型目錄")
                }
                if (temporaryFile.exists() && !temporaryFile.delete()) {
                    throw IOException("無法清除未完成的模型下載")
                }

                _status.value = MosaicDetectorModelStatus.Downloading(0f)
                val request = Request.Builder().url(normalized.downloadUrl).build()
                val call = httpClient.newCall(request)
                val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) call.cancel()
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytesRead = 0L
                var lastReportedPercent = -1

                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        if (!response.request.url.isHttps) {
                            throw IOException("偵測模型下載不可重新導向到非 HTTPS 網址")
                        }
                        val body = response.body ?: throw IOException("模型回應內容為空")
                        val contentLength = body.contentLength()
                        if (contentLength > MaximumModelBytes) {
                            throw IOException("偵測模型超過 ${MaximumModelBytes / 1024 / 1024} MB 上限")
                        }

                        body.byteStream().use { input ->
                            FileOutputStream(temporaryFile).use { output ->
                                val buffer = ByteArray(BufferSize)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (totalBytesRead + count > MaximumModelBytes) {
                                        throw IOException(
                                            "偵測模型超過 ${MaximumModelBytes / 1024 / 1024} MB 上限"
                                        )
                                    }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    totalBytesRead += count

                                    if (contentLength > 0L) {
                                        val percent = (totalBytesRead * 100L / contentLength)
                                            .toInt()
                                            .coerceIn(0, 100)
                                        if (percent != lastReportedPercent) {
                                            lastReportedPercent = percent
                                            _status.value =
                                                MosaicDetectorModelStatus.Downloading(percent / 100f)
                                        }
                                    } else {
                                        _status.value = MosaicDetectorModelStatus.Downloading(null)
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                    }
                } finally {
                    cancellationHandle.dispose()
                }

                val actualSha256 = digest.digest().toHexString()
                if (actualSha256 != normalized.sha256) {
                    throw IOException("下載模型的 SHA-256 不符")
                }

                _status.value = MosaicDetectorModelStatus.Verifying
                val info = OnnxMosaicDetector.inspect(temporaryFile)
                if (modelFile.exists() && !modelFile.delete()) {
                    throw IOException("無法取代舊的偵測模型")
                }
                if (!temporaryFile.renameTo(modelFile)) {
                    throw IOException("無法完成偵測模型下載")
                }

                validatedSha256 = normalized.sha256
                validatedInfo = info
                _status.value = MosaicDetectorModelStatus.Ready(info)
                Result.success(modelFile)
            } catch (error: CancellationException) {
                temporaryFile.delete()
                clearValidatedModel()
                _status.value = MosaicDetectorModelStatus.NotDownloaded
                throw error
            } catch (error: IOException) {
                temporaryFile.delete()
                if (!currentCoroutineContext().isActive) {
                    clearValidatedModel()
                    _status.value = MosaicDetectorModelStatus.NotDownloaded
                    throw CancellationException("Detector download cancelled").apply {
                        initCause(error)
                    }
                }
                val message = error.message ?: "偵測模型下載失敗"
                _status.value = MosaicDetectorModelStatus.Error(message)
                Log.e(Tag, message, error)
                Result.failure(error)
            } catch (error: OrtException) {
                temporaryFile.delete()
                val message = error.message ?: "偵測模型不是相容的 ONNX 模型"
                _status.value = MosaicDetectorModelStatus.Error(message)
                Log.e(Tag, message, error)
                Result.failure(error)
            } catch (error: RuntimeException) {
                temporaryFile.delete()
                val message = error.message ?: "偵測模型格式錯誤"
                _status.value = MosaicDetectorModelStatus.Error(message)
                Log.e(Tag, message, error)
                Result.failure(error)
            }
        }
    }

    suspend fun delete(): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (modelFile.exists() && !modelFile.delete()) {
                    throw IOException("無法刪除偵測模型")
                }
                if (temporaryFile.exists() && !temporaryFile.delete()) {
                    throw IOException("無法刪除未完成的偵測模型")
                }
                clearValidatedModel()
                _status.value = MosaicDetectorModelStatus.NotDownloaded
                Result.success(Unit)
            } catch (error: IOException) {
                val message = error.message ?: "偵測模型刪除失敗"
                _status.value = MosaicDetectorModelStatus.Error(message)
                Result.failure(error)
            }
        }
    }

    private fun validateFile(spec: MosaicDetectorModelSpec) {
        val actualSha256 = modelFile.sha256()
        if (actualSha256 != spec.sha256) {
            throw IOException("已下載模型的 SHA-256 不符，請刪除後重新下載")
        }
        val info = OnnxMosaicDetector.inspect(modelFile)
        validatedSha256 = spec.sha256
        validatedInfo = info
        _status.value = MosaicDetectorModelStatus.Ready(info)
    }

    private fun clearValidatedModel() {
        validatedSha256 = null
        validatedInfo = null
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(BufferSize)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val Tag = "MosaicDetectorRepo"
        private const val ModelFileName = "custom_mosaic_detector.onnx"
        private const val BufferSize = 64 * 1024
        private const val MaximumModelBytes = 256L * 1024L * 1024L
    }
}
