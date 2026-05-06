package com.example.autosrtplayer.data.playlist

private const val DesktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
private const val MissavReferrer = "https://missav.ai/"

class MissavPlaylistBuilder {
    fun build(
        id: String,
        detailUrl: String,
        mediaUrl: String,
        title: String?,
        userAgent: String
    ): String {
        val displayTitle = title?.trim().takeIf { !it.isNullOrBlank() } ?: id.trim()
        val resolvedUserAgent = userAgent.trim().ifBlank { DesktopUserAgent }
        val referrer = if (detailUrl.isBlank()) MissavReferrer else MissavReferrer
        return listOf(
            "#EXTM3U",
            "#EXTINF:-1,$displayTitle",
            "#EXTVLCOPT:http-user-agent=$resolvedUserAgent",
            "#EXTVLCOPT:http-referrer=$referrer",
            mediaUrl.trim()
        ).joinToString("\n")
    }
}
