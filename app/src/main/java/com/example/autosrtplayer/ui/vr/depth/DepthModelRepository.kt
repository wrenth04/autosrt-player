package com.example.autosrtplayer.ui.vr.depth

import android.content.Context
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
 */
class DepthModelRepository(private val context: Context) {
    private val httpClient = OkHttpClient()
    private val modelDir = File(context.filesDir, "depth_models")

    private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val modelStatuses: StateFlow<Map<String, ModelStatus>> = _modelStatuses

    init {
        modelDir.mkdirs()
        updateModelStatuses()
    }

    /**
     * Returns the file path for a downloaded model, or null if not downloaded.
     */
    fun getModelFile(modelId: String): File? {
        val file = File(modelDir, DepthModel.getModelFileName(modelId))
        return if (file.exists()) file else null
    }

    /**
     * Returns the total size of all downloaded models in bytes.
     */
    fun getTotalModelSize(): Long {
        return modelDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Downloads a model from its URL.
     */
    suspend fun downloadModel(model: DepthModel): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(modelDir, DepthModel.getModelFileName(model.id))

            // Update status to downloading
            updateStatus(model.id, ModelStatus.Downloading(0f))

            val request = Request.Builder()
                .url(model.downloadUrl)
                .build()

            val response = httpClient.newCall(request).execute()
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
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(outputFile)

            try {
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
                updateStatus(model.id, ModelStatus.Downloaded)
                Result.success(outputFile)
            } catch (e: Exception) {
                outputFile.delete()
                val error = e.message ?: "Download failed"
                updateStatus(model.id, ModelStatus.Error(error))
                Result.failure(e)
            } finally {
                inputStream.close()
                outputStream.close()
            }
        } catch (e: Exception) {
            val error = e.message ?: "Download failed"
            updateStatus(model.id, ModelStatus.Error(error))
            Result.failure(e)
        }
    }

    /**
     * Deletes a downloaded model to free up space.
     */
    suspend fun deleteModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(modelDir, DepthModel.getModelFileName(modelId))
            if (file.exists() && file.delete()) {
                updateStatus(modelId, ModelStatus.NotDownloaded)
                Result.success(Unit)
            } else {
                Result.failure(IOException("Failed to delete model"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates the status map for all available models.
     */
    private fun updateModelStatuses() {
        val statuses = DepthModel.availableModels().associate { model ->
            val file = File(modelDir, DepthModel.getModelFileName(model.id))
            val status = if (file.exists()) {
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
}
