package com.example.autosrtplayer.data.favorites

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

object FavoriteCodec {
    fun encode(items: List<FavoriteItem>): String {
        return items.asSequence()
            .filter { it.id.isNotBlank() }
            .joinToString(separator = "\n") { item ->
                listOf(
                    encodeField(item.id),
                    encodeField(item.title.orEmpty()),
                    item.addedAtMs.toString()
                ).joinToString(separator = "|")
            }
    }

    fun decode(raw: String?): List<FavoriteItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = linkedSetOf<String>()
        return buildList {
            raw.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split('|', limit = 3)
                if (parts.size != 3) return@forEach
                val id = decodeField(parts[0]).trim()
                if (id.isBlank()) return@forEach
                val key = id.lowercase(Locale.ROOT)
                if (!seen.add(key)) return@forEach
                val title = decodeField(parts[1]).takeIf { it.isNotBlank() }
                val addedAtMs = parts[2].toLongOrNull() ?: 0L
                add(FavoriteItem(id = id, title = title, addedAtMs = addedAtMs))
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
