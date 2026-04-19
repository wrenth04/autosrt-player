package com.example.autosrtplayer.data.intent

import android.net.Uri

sealed interface LaunchTarget {
    data class Url(val value: String) : LaunchTarget
    data class SourceId(val value: String) : LaunchTarget
}

object SharedUrlExtractor {
    private val UrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private const val AppSchemePrefix = "autosrt-player://"

    fun extractM3uUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        return UrlRegex.findAll(sharedText)
            .map { candidate -> sanitizeUrl(candidate.value) }
            .firstOrNull(::isM3uUrl)
    }

    fun extractLaunchTarget(dataString: String?): LaunchTarget? {
        if (dataString.isNullOrBlank()) return null
        if (!dataString.startsWith(AppSchemePrefix, ignoreCase = true)) return null

        val rawPayload = dataString.substring(AppSchemePrefix.length).trim()
        if (rawPayload.isBlank()) return null
        val decodedPayload = Uri.decode(rawPayload).trim()

        val sanitizedPayload = sanitizeUrl(normalizePayloadUrl(decodedPayload))
        if (isM3uUrl(sanitizedPayload)) {
            return LaunchTarget.Url(sanitizedPayload)
        }

        val id = decodedPayload.substringBefore('?').substringBefore('#').trim()
        return id.takeIf { it.isNotBlank() }?.let(LaunchTarget::SourceId)
    }

    private fun sanitizeUrl(url: String): String {
        return url.trimEnd('.', ',', ';', ')', ']', '"', '\'')
    }

    private fun normalizePayloadUrl(payload: String): String {
        return when {
            payload.startsWith("https//", ignoreCase = true) ->
                payload.replaceFirst("https//", "https://", ignoreCase = true)
            payload.startsWith("http//", ignoreCase = true) ->
                payload.replaceFirst("http//", "http://", ignoreCase = true)
            else -> payload
        }
    }

    private fun isM3uUrl(url: String): Boolean {
        val normalized = url.substringBefore('#')
        return normalized.contains(".m3u8", ignoreCase = true) ||
            normalized.contains(".m3u", ignoreCase = true)
    }
}
