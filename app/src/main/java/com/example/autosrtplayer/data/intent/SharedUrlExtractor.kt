package com.example.autosrtplayer.data.intent

object SharedUrlExtractor {
    private val UrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun extractM3uUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        return UrlRegex.findAll(sharedText)
            .map { candidate -> candidate.value.trimEnd('.', ',', ';', ')', ']', '"', '\'') }
            .firstOrNull { url ->
                val normalized = url.substringBefore('#')
                normalized.contains(".m3u8", ignoreCase = true) ||
                    normalized.contains(".m3u", ignoreCase = true)
            }
    }
}
