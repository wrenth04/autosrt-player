package com.example.autosrtplayer.data.restoration

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class MosaicDetectorModelSpec(
    val downloadUrl: String,
    val sha256: String
) {
    fun normalized(): MosaicDetectorModelSpec {
        return copy(
            downloadUrl = downloadUrl.trim(),
            sha256 = sha256.trim().lowercase()
        )
    }

    fun validationError(): String? {
        val normalized = normalized()
        val parsedUrl = normalized.downloadUrl.toHttpUrlOrNull()
        if (parsedUrl == null || !parsedUrl.isHttps) {
            return "模型網址必須使用 HTTPS"
        }
        if (normalized.sha256.length != 64 ||
            normalized.sha256.any { it !in "0123456789abcdef" }
        ) {
            return "SHA-256 必須是 64 個十六進位字元"
        }
        return null
    }

    companion object {
        const val DeepMosaicsFileSizeBytes = 47_540_486L
        const val DeepMosaicsModelCardUrl =
            "https://huggingface.co/LIGA1998/DeepMosaics-ONNX"
        private const val DeepMosaicsDownloadUrl =
            "https://huggingface.co/LIGA1998/DeepMosaics-ONNX/resolve/" +
                "cead5e065f22d817078a451350975f80e9a93f7d/MosaicPosition.onnx"
        private const val DeepMosaicsSha256 =
            "fa16f91573aa09973cf3dc91e2fc1113f55fde1adb46d65548946cf5c88b4cbe"

        fun deepMosaics(): MosaicDetectorModelSpec {
            return MosaicDetectorModelSpec(
                downloadUrl = DeepMosaicsDownloadUrl,
                sha256 = DeepMosaicsSha256
            )
        }
    }
}

data class MosaicDetectorModelInfo(
    val inputTensorName: String,
    val outputTensorName: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int?,
    val outputHeight: Int?
)

sealed interface MosaicDetectorModelStatus {
    data object NotConfigured : MosaicDetectorModelStatus
    data object NotDownloaded : MosaicDetectorModelStatus
    data object Verifying : MosaicDetectorModelStatus
    data class Downloading(val progress: Float?) : MosaicDetectorModelStatus
    data class Ready(val info: MosaicDetectorModelInfo) : MosaicDetectorModelStatus
    data class Error(val message: String) : MosaicDetectorModelStatus
}
