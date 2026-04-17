package com.example.autosrtplayer.data.playlist

class PlaylistParser {
    fun parse(content: String, playlistUrl: String? = null): PlaylistEntry {
        var title: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var subtitleUrl: String? = null
        var mediaUrl: String? = null

        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                when {
                    line.startsWith("#EXTINF:") -> {
                        title = line.substringAfter(',', missingDelimiterValue = "").ifBlank { null }
                    }

                    line.startsWith("#EXTVLCOPT:") -> {
                        val option = line.removePrefix("#EXTVLCOPT:")
                        val key = option.substringBefore('=', missingDelimiterValue = "")
                        val value = option.substringAfter('=', missingDelimiterValue = "").ifBlank { null }
                        when (key) {
                            "http-user-agent" -> userAgent = value
                            "http-referrer" -> referrer = value
                        }
                    }

                    line.startsWith("#EXTSUB:") -> {
                        subtitleUrl = line.removePrefix("#EXTSUB:").ifBlank { null }
                    }

                    !line.startsWith("#") && mediaUrl == null -> {
                        mediaUrl = line
                    }
                }
            }

        return PlaylistEntry(
            title = title,
            mediaUrl = mediaUrl ?: throw IllegalArgumentException("Playlist 缺少媒體 URL"),
            userAgent = userAgent,
            referrer = referrer,
            subtitleUrl = subtitleUrl ?: deriveSubtitleUrl(playlistUrl)
        )
    }

    private fun deriveSubtitleUrl(playlistUrl: String?): String? {
        val source = playlistUrl?.trim()?.substringBefore('?')?.substringBefore('#') ?: return null
        return when {
            source.endsWith(".m3u8", ignoreCase = true) -> source.dropLast(5) + ".srt"
            source.endsWith(".m3u", ignoreCase = true) -> source.dropLast(4) + ".srt"
            else -> null
        }
    }
}
