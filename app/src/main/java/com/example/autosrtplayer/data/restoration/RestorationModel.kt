package com.example.autosrtplayer.data.restoration

data class RestorationModel(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val version: String,
    val inputTensorName: String,
    val previousInputTensorName: String,
    val outputTensorName: String,
    val inputSize: Int,
    val temporalFrameCount: Int,
    val license: String,
    val modelCardUrl: String,
    val sourceUrl: String
) {
    val fileSizeMb: Float
        get() = fileSizeBytes / (1024f * 1024f)

    companion object {
        const val DefaultModelId = "deepmosaics-bvdnet-video-onnx"

        fun availableModels(): List<RestorationModel> = listOf(
            RestorationModel(
                id = DefaultModelId,
                name = "DeepMosaics BVDNet Video",
                description = "使用 5 影格時序資訊與上一張輸出，專門推測馬賽克區域。",
                downloadUrl = "https://huggingface.co/LIGA1998/DeepMosaics-ONNX/resolve/cead5e065f22d817078a451350975f80e9a93f7d/DeepMosaics.onnx",
                fileSizeBytes = 213_449_721L,
                sha256 = "a30cd9bd518afc7169edf09aa64824ea61d9a24aa641433c7db5cc298585d45b",
                version = "opset18-cead5e0",
                inputTensorName = "input",
                previousInputTensorName = "input.17",
                outputTensorName = "output",
                inputSize = 256,
                temporalFrameCount = 5,
                license = "GPL-3.0；含 pix2pixHD 研究／非商用元件",
                modelCardUrl = "https://huggingface.co/LIGA1998/DeepMosaics-ONNX",
                sourceUrl = "https://github.com/HypoX64/DeepMosaics"
            )
        )

        fun getModelFileName(model: RestorationModel): String {
            return "${model.id}_${model.version}.onnx"
        }

        fun validateModel(model: RestorationModel): String? {
            if (model.id.isBlank()) return "Model ID cannot be blank"
            if (!model.downloadUrl.startsWith("https://")) return "Model URL must use HTTPS"
            if (model.fileSizeBytes <= 0L) return "Model size must be positive"
            if (model.sha256.length != 64 || model.sha256.any { it !in "0123456789abcdef" }) {
                return "Model SHA-256 must be lowercase hexadecimal"
            }
            if (model.inputTensorName.isBlank() ||
                model.previousInputTensorName.isBlank() ||
                model.outputTensorName.isBlank()
            ) {
                return "Tensor names cannot be blank"
            }
            if (model.inputSize <= 0 || model.temporalFrameCount <= 0) {
                return "Model dimensions must be positive"
            }
            return null
        }

        fun validateCatalog(): List<String> {
            val models = availableModels()
            val errors = mutableListOf<String>()
            val duplicateIds = models
                .groupingBy(RestorationModel::id)
                .eachCount()
                .filterValues { it > 1 }
                .keys

            if (duplicateIds.isNotEmpty()) {
                errors += "Duplicate model IDs: ${duplicateIds.joinToString()}"
            }
            models.forEach { model ->
                validateModel(model)?.let { errors += "${model.id}: $it" }
            }
            return errors
        }
    }
}

sealed interface RestorationModelStatus {
    data object Verifying : RestorationModelStatus
    data object NotDownloaded : RestorationModelStatus
    data class Downloading(val progress: Float) : RestorationModelStatus
    data object Downloaded : RestorationModelStatus
    data class Error(val message: String) : RestorationModelStatus
}
