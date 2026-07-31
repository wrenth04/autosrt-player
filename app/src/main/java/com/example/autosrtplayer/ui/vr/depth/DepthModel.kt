package com.example.autosrtplayer.ui.vr.depth

/**
 * Represents an available depth estimation model with metadata.
 */
data class DepthModel(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val fileSizeMB: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val license: String,
    val recommended: Boolean = false
) {
    companion object {
        /**
         * List of available depth models for download.
         * These are verified TFLite models from reliable sources.
         */
        fun availableModels(): List<DepthModel> = listOf(
            DepthModel(
                id = "midas_small_256_fp16",
                name = "MiDaS Small FP16",
                description = "官方 LiteRT 優化版本，在 Pixel 8a 上驗證過。推論速度快，適合大部分裝置。",
                downloadUrl = "https://huggingface.co/litert-community/MiDaS-small/resolve/main/midas_small_256_fp16.tflite",
                fileSizeMB = 33,
                inputWidth = 256,
                inputHeight = 256,
                license = "MIT",
                recommended = true
            )
        )

        /**
         * Returns the filename for a given model ID.
         */
        fun getModelFileName(modelId: String): String {
            return "$modelId.tflite"
        }
    }
}

/**
 * Status of a depth model on the device.
 */
sealed class ModelStatus {
    data object NotDownloaded : ModelStatus()
    data class Downloading(val progress: Float) : ModelStatus()
    data object Downloaded : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}
