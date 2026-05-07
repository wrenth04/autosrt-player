package com.example.autosrtplayer.data.favorites

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

object FavoriteCodec {
    fun encode(items: List<FavoriteItem>): String {
        return items.asSequence()
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n") { encodeField(it) }
    }

    fun decode(raw: String?): List<FavoriteItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = linkedSetOf<String>()
        return buildList {
            raw.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val id = decodeField(line).trim()
                if (id.isBlank()) return@forEach
                val key = id.lowercase(Locale.ROOT)
                if (!seen.add(key)) return@forEach
                add(FavoriteItem(id = id))
            }
        }
    }

    private fun encodeField(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeField(value: String): String {
        return runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrElse { "" }
    }
}
