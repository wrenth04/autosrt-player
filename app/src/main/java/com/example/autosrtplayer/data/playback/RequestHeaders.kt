package com.example.autosrtplayer.data.playback

import android.net.Uri

internal fun buildRequestHeaders(userAgent: String?, referrer: String?): Map<String, String> = buildMap {
    userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    referrer?.takeIf { it.isNotBlank() }?.let { referer ->
        put("Referer", referer)
        // Some upstream gateways incorrectly validate against "Referrer" spelling.
        put("Referrer", referer)

        val origin = runCatching {
            val uri = Uri.parse(referer)
            val scheme = uri.scheme?.takeIf { it.isNotBlank() }
            val host = uri.host?.takeIf { it.isNotBlank() }
            if (scheme != null && host != null) {
                if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
            } else {
                null
            }
        }.getOrNull()
        origin?.let { put("Origin", it) }
    }
}
