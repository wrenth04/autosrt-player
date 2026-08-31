package com.example.autosrtplayer.data.restoration

import android.content.Context
import android.util.Log
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

class RestorationModelRepository(
    context: Context,
    private val httpClient: OkHttpClient
) {
    private val modelDirectory = File(context.filesDir, "restoration_models")
    private val validatedFiles = mutableMapOf<String, File>()
    private val downloadMutex = Mutex()
    private val _modelStatuses = MutableStateFlow<Map<String, RestorationModelStatus>>(
        RestorationModel.availableModels().associate { model ->
            model.id to RestorationModelStatus.Verifying
        }
    )

    val modelStatuses: StateFlow<Map<String, RestorationModelStatus>> = _modelStatuses

    init {
        val catalogErrors = RestorationModel.validateCatalog()
        check(catalogErrors.isEmpty()) { catalogErrors.joinToString() }
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            Log.e(Tag, "Could not create model directory: ${modelDirectory.absolutePath}")
        }
    }

    fun getModel(modelId: String): RestorationModel? {
        return RestorationModel.availableModels().find { it.id == modelId }
    }

    fun getModelFile(model: RestorationModel): File? {
        synchronized(validatedFiles) {
            validatedFiles[model.id]?.let { cached ->
                if (cached.isFile && cached.length() == model.fileSizeBytes) {
                    return cached
                }
                validatedFiles.remove(model.id)
            }
        }

        return null
    }

    suspend fun refreshModelStatuses() = downloadMutex.withLock {
        val statuses = withContext(Dispatchers.IO) {
            RestorationModel.availableModels().associate { model ->
                val file = File(modelDirectory, RestorationModel.getModelFileName(model))
                val validationError = validateModelFile(model, file)
                val status = when {
                    !file.exists() -> RestorationModelStatus.NotDownloaded
                    validationError != null -> RestorationModelStatus.Error(validationError)
                    else -> {
                        synchronized(validatedFiles) {
                            validatedFiles[model.id] = file
                        }
                        RestorationModelStatus.Downloaded
                    }
                }
                if (status !is RestorationModelStatus.Downloaded) {
                    synchronized(validatedFiles) {
                        validatedFiles.remove(model.id)
                    }
                }
                model.id to status
            }
        }
        _modelStatuses.value = statuses
    }

    suspend fun downloadModel(model: RestorationModel): Result<File> = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val fileName = RestorationModel.getModelFileName(model)
            val temporaryFile = File(modelDirectory, "$fileName.part")
            val finalFile = File(modelDirectory, fileName)

            try {
                if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                    throw IOException("Could not create the model directory")
                }
                if (temporaryFile.exists() && !temporaryFile.delete()) {
                    throw IOException("Could not remove an incomplete model download")
                }

                updateStatus(model.id, RestorationModelStatus.Downloading(0f))
                val request = Request.Builder().url(model.downloadUrl).build()
                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytesRead = 0L
                var lastReportedPercent = -1
                val call = httpClient.newCall(request)
                val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        call.cancel()
                    }
                }

                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Empty model response")
                        val contentLength = body.contentLength()
                        if (contentLength > 0L && contentLength != model.fileSizeBytes) {
                            throw IOException(
                                "Unexpected model size: $contentLength bytes; expected ${model.fileSizeBytes}"
                            )
                        }

                        body.byteStream().use { input ->
                            FileOutputStream(temporaryFile).use { output ->
                                val buffer = ByteArray(DefaultBufferSize)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (totalBytesRead + count > model.fileSizeBytes) {
                                        throw IOException("Model response exceeds the expected size")
                                    }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    totalBytesRead += count
                                    val reportedPercent =
                                        (totalBytesRead * 100L / model.fileSizeBytes).toInt()
                                    if (reportedPercent != lastReportedPercent) {
                                        lastReportedPercent = reportedPercent
                                        updateStatus(
                                            model.id,
                                            RestorationModelStatus.Downloading(
                                                (reportedPercent / 100f).coerceIn(0f, 1f)
                                            )
                                        )
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
                if (totalBytesRead != model.fileSizeBytes) {
                    throw IOException(
                        "Downloaded model size mismatch: $totalBytesRead bytes; expected ${model.fileSizeBytes}"
                    )
                }
                if (actualSha256 != model.sha256) {
                    throw IOException("Downloaded model failed SHA-256 verification")
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw IOException("Could not replace the existing model")
                }
                if (!temporaryFile.renameTo(finalFile)) {
                    throw IOException("Could not finalize the model download")
                }

                synchronized(validatedFiles) {
                    validatedFiles[model.id] = finalFile
                }
                updateStatus(model.id, RestorationModelStatus.Downloaded)
                Log.i(Tag, "Downloaded and verified restoration model ${model.id}")
                Result.success(finalFile)
            } catch (error: CancellationException) {
                temporaryFile.delete()
                updateStatus(model.id, RestorationModelStatus.NotDownloaded)
                throw error
            } catch (error: IOException) {
                temporaryFile.delete()
                if (!currentCoroutineContext().isActive) {
                    updateStatus(model.id, RestorationModelStatus.NotDownloaded)
                    throw CancellationException("Model download cancelled").apply {
                        initCause(error)
                    }
                }
                val message = error.message ?: "Model download failed"
                updateStatus(model.id, RestorationModelStatus.Error(message))
                Log.e(Tag, "Failed to download restoration model ${model.id}", error)
                Result.failure(error)
            } catch (error: RuntimeException) {
                temporaryFile.delete()
                val message = error.message ?: "Model validation failed"
                updateStatus(model.id, RestorationModelStatus.Error(message))
                Log.e(Tag, "Failed to validate restoration model ${model.id}", error)
                Result.failure(error)
            }
        }
    }

    suspend fun deleteModel(model: RestorationModel): Result<Unit> = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(modelDirectory, RestorationModel.getModelFileName(model))
            val temporaryFile = File(modelDirectory, "${file.name}.part")
            try {
                if (file.exists() && !file.delete()) {
                    throw IOException("Could not delete the model")
                }
                if (temporaryFile.exists() && !temporaryFile.delete()) {
                    throw IOException("Could not delete the incomplete model download")
                }
                synchronized(validatedFiles) {
                    validatedFiles.remove(model.id)
                }
                updateStatus(model.id, RestorationModelStatus.NotDownloaded)
                Result.success(Unit)
            } catch (error: IOException) {
                val message = error.message ?: "Model deletion failed"
                updateStatus(model.id, RestorationModelStatus.Error(message))
                Log.e(Tag, "Failed to delete restoration model ${model.id}", error)
                Result.failure(error)
            }
        }
    }

    private fun validateModelFile(model: RestorationModel, file: File): String? {
        if (!file.isFile) return "Model file is not available"
        if (file.length() != model.fileSizeBytes) {
            return "Model file size verification failed"
        }
        val actualSha256 = try {
            file.sha256()
        } catch (error: IOException) {
            Log.e(Tag, "Could not hash model ${model.id}", error)
            return "Model file could not be verified"
        }
        return if (actualSha256 == model.sha256) null else "Model SHA-256 verification failed"
    }

    private fun updateStatus(modelId: String, status: RestorationModelStatus) {
        _modelStatuses.value = _modelStatuses.value.toMutableMap().apply {
            put(modelId, status)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DefaultBufferSize)
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
        private const val Tag = "RestorationModelRepo"
        private const val DefaultBufferSize = 64 * 1024
    }
}
