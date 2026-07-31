package com.example.autosrtplayer.ui.vr.depth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Repository for managing depth model downloads and storage.
 * Handles ONNX model downloads with integrity validation and migration from legacy TFLite.
 */
class DepthModelRepository(private val context: Context) {
    private val httpClient = OkHttpClient()
    private val modelDir = File(context.filesDir, "depth_models")

    private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val modelStatuses: StateFlow<Map<String, ModelStatus>> = _modelStatuses

    init {
        modelDir.mkdirs()
        cleanupLegacyTFLiteModels()
        updateModelStatuses()
    }

    /**
     * Removes legacy TFLite model files that are no longer compatible with ONNX runtime.
     */
    private fun cleanupLegacyTFLiteModels() {
        try {
            modelDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tflite")) {
                    val deleted = file.delete()
                    Log.i(TAG, "Cleaned up legacy TFLite model: ${file.name}, deleted=$deleted")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup legacy TFLite models", e)
        }
    }

    /**
     * Returns the model object for a given ID, or null if not in catalog.
     */
    fun getModel(modelId: String): DepthModel? {
        return DepthModel.availableModels().find { it.id == modelId }
    }

    /**
     * Returns the file for a downloaded and validated model, or null if not available.
     */
    fun getModelFile(model: DepthModel): File? {
        val file = File(modelDir, DepthModel.getModelFileName(model))
        if (!file.exists()) return null

        // Validate file size is reasonable (±20%)
        val sizeMB = file.length() / (1024 * 1024)
        val expectedSizeMB = model.fileSizeMB.toLong()
        if (sizeMB < expectedSizeMB * 0.8 || sizeMB > expectedSizeMB * 1.2) {
            Log.w(TAG, "Model file size mismatch: expected ~${expectedSizeMB}MB, got ${sizeMB}MB")
            return null
        }

        return file
    }

    /**
     * Returns the total size of all downloaded models in bytes.
     */
    fun getTotalModelSize(): Long {
        return modelDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Downloads a model from its URL with progress tracking and integrity validation.
     */
    suspend fun downloadModel(model: DepthModel): Result<File> = withContext(Dispatchers.IO) {
        val tempFile = File(modelDir, "${DepthModel.getModelFileName(model)}.tmp")
        val finalFile = File(modelDir, DepthModel.getModelFileName(model))

        try {
            // Clean up any incomplete previous download
            tempFile.delete()

            updateStatus(model.id, ModelStatus.Downloading(0f))

            val request = Request.Builder()
                .url(model.downloadUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = "HTTP ${response.code}"
                    updateStatus(model.id, ModelStatus.Error(error))
                    return@withContext Result.failure(IOException(error))
                }

                val body = response.body ?: run {
                    val error = "Empty response body"
                    updateStatus(model.id, ModelStatus.Error(error))
                    return@withContext Result.failure(IOException(error))
                }

                val contentLength = body.contentLength()
                val expectedBytes = model.fileSizeMB * 1024L * 1024L

                // Validate content length if available
                if (contentLength > 0 && (contentLength < expectedBytes * 0.8 || contentLength > expectedBytes * 1.2)) {
                    val error = "Unexpected file size: ${contentLength / 1024 / 1024}MB (expected ~${model.fileSizeMB}MB)"
                    updateStatus(model.id, ModelStatus.Error(error))
                    body.close()
                    return@withContext Result.failure(IOException(error))
                }

                body.byteStream().use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (contentLength > 0) {
                                val progress = (totalBytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                                updateStatus(model.id, ModelStatus.Downloading(progress))
                            }
                        }

                        outputStream.flush()
                    }
                }
            }

            // Validate downloaded file size (±20%)
            val actualSizeMB = tempFile.length() / (1024 * 1024)
            if (actualSizeMB < model.fileSizeMB * 0.8 || actualSizeMB > model.fileSizeMB * 1.2) {
                tempFile.delete()
                val error = "Downloaded file size mismatch: ${actualSizeMB}MB (expected ~${model.fileSizeMB}MB)"
                updateStatus(model.id, ModelStatus.Error(error))
                return@withContext Result.failure(IOException(error))
            }

            // Atomically promote temp file to final location
            finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                val error = "Failed to finalize download"
                updateStatus(model.id, ModelStatus.Error(error))
                return@withContext Result.failure(IOException(error))
            }

            updateStatus(model.id, ModelStatus.Downloaded)
            Log.i(TAG, "Successfully downloaded model: ${model.name}")
            Result.success(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            val error = e.message ?: "Download failed"
            updateStatus(model.id, ModelStatus.Error(error))
            Log.e(TAG, "Download failed for ${model.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes a downloaded model to free up space.
     */
    suspend fun deleteModel(model: DepthModel): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(modelDir, DepthModel.getModelFileName(model))
            if (file.exists() && file.delete()) {
                updateStatus(model.id, ModelStatus.NotDownloaded)
                Log.i(TAG, "Deleted model: ${model.name}")
                Result.success(Unit)
            } else {
                Result.failure(IOException("Failed to delete model"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${model.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates the status map for all available models.
     */
    private fun updateModelStatuses() {
        val statuses = DepthModel.availableModels().associate { model ->
            val file = getModelFile(model)
            val status = if (file != null) {
                ModelStatus.Downloaded
            } else {
                ModelStatus.NotDownloaded
            }
            model.id to status
        }
        _modelStatuses.value = statuses
    }

    /**
     * Updates the status of a specific model.
     */
    private fun updateStatus(modelId: String, status: ModelStatus) {
        _modelStatuses.value = _modelStatuses.value.toMutableMap().apply {
            put(modelId, status)
        }
    }

    /**
     * Cancels any ongoing downloads and releases resources.
     */
    fun release() {
        // OkHttp client will be garbage collected
    }

    companion object {
        private const val TAG = "DepthModelRepository"
    }
}
