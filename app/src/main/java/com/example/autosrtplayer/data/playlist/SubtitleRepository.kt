package com.example.autosrtplayer.data.playlist

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class SubtitleRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun resolveSubtitleUri(
        context: Context,
        subtitleUrl: String,
        userAgent: String?,
        referrer: String?
    ): Uri = withContext(Dispatchers.IO) {
        val parsedUri = Uri.parse(subtitleUrl)
        if (!parsedUri.scheme.isRemoteHttp()) {
            return@withContext parsedUri
        }

        runCatching {
            downloadRemoteSubtitle(context, subtitleUrl, userAgent, referrer)
        }.getOrElse {
            parsedUri
        }
    }

    private fun downloadRemoteSubtitle(
        context: Context,
        subtitleUrl: String,
        userAgent: String?,
        referrer: String?
    ): Uri {
        val subtitleDir = File(context.cacheDir, CacheDirectoryName).apply { mkdirs() }
        val extension = subtitleFileExtension(subtitleUrl)
        val baseName = sha256(subtitleUrl)
        subtitleDir.listFiles { file ->
            file.name.startsWith(baseName) && file.name.endsWith(extension)
        }?.forEach { it.delete() }

        val subtitleFile = File(subtitleDir, "${baseName}-${System.currentTimeMillis()}$extension")
        val tempFile = File(subtitleDir, "${subtitleFile.name}.download")
        val request = Request.Builder()
            .url(subtitleUrl)
            .apply {
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                referrer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下載字幕失敗: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("字幕內容為空")
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (!tempFile.renameTo(subtitleFile)) {
            tempFile.copyTo(subtitleFile, overwrite = true)
            tempFile.delete()
        }

        return Uri.fromFile(subtitleFile)
    }

    private fun subtitleFileExtension(subtitleUrl: String): String {
        val normalized = subtitleUrl.substringBefore('?').substringBefore('#').lowercase()
        return when {
            normalized.endsWith(".vtt") -> ".vtt"
            normalized.endsWith(".srt") -> ".srt"
            else -> ".srt"
        }
    }

    private fun String?.isRemoteHttp(): Boolean {
        return when (this?.lowercase()) {
            "http", "https" -> true
            else -> false
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private const val CacheDirectoryName = "subtitles"
    }
}
