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
    val outputTensorName: String,
    val outputScale: Int,
    val maxInputEdge: Int,
    val license: String,
    val modelCardUrl: String,
    val sourceUrl: String
) {
    val fileSizeMb: Float
        get() = fileSizeBytes / (1024f * 1024f)

    companion object {
        const val DefaultModelId = "realesr-general-x4v3-onnx"

        fun availableModels(): List<RestorationModel> = listOf(
            RestorationModel(
                id = DefaultModelId,
                name = "Real-ESRGAN General x4v3",
                description = "局部超解析與去方塊預覽，適合一般實拍影片。",
                downloadUrl = "https://huggingface.co/CoderViking/realesr-general-x4v3-onnx/resolve/c6a971706797c7502945a2b4c4274fce4900d4ab/realesr-general-x4v3.onnx",
                fileSizeBytes = 4_866_417L,
                sha256 = "1940a93ee08283a0a7286183186357b1688fe9fa8ede74604b424586aaddf112",
                version = "x4v3-opset17",
                inputTensorName = "input",
                outputTensorName = "output",
                outputScale = 4,
                maxInputEdge = 96,
                license = "BSD-3-Clause",
                modelCardUrl = "https://huggingface.co/CoderViking/realesr-general-x4v3-onnx",
                sourceUrl = "https://github.com/xinntao/Real-ESRGAN"
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
            if (model.inputTensorName.isBlank() || model.outputTensorName.isBlank()) {
                return "Tensor names cannot be blank"
            }
            if (model.outputScale <= 0 || model.maxInputEdge <= 0) {
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
