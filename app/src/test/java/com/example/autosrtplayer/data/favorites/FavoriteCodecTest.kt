package com.example.autosrtplayer.data.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class FavoriteCodecTest {
    @Test
    fun `decode empty or malformed json returns empty list`() {
        assertTrue(FavoriteCodec.decode(null).isEmpty())
        assertTrue(FavoriteCodec.decode("").isEmpty())
        assertTrue(FavoriteCodec.decode("not json").isEmpty())
    }

    @Test
    fun `encode and decode preserve favorite fields`() {
        val items = listOf(
            FavoriteItem(id = "ABCD-123", title = "Title One", addedAtMs = 123L),
            FavoriteItem(id = "EFGH-456", title = null, addedAtMs = 456L)
        )

        val decoded = FavoriteCodec.decode(FavoriteCodec.encode(items))

        assertEquals(items, decoded)
    }

    @Test
    fun `decode skips blank ids and deduplicates ids case insensitively`() {
        val decoded = FavoriteCodec.decode(
            listOf(
                encodedLine(" ABCD-123 ", "First", 10L),
                encodedLine("", "Ignored", 11L),
                encodedLine("abcd-123", "Duplicate", 12L),
                encodedLine("WXYZ-999", "Second", 13L)
            ).joinToString(separator = "\n")
        )

        assertEquals(2, decoded.size)
        assertEquals("ABCD-123", decoded[0].id)
        assertEquals("First", decoded[0].title)
        assertEquals(10L, decoded[0].addedAtMs)
        assertEquals("WXYZ-999", decoded[1].id)
    }

    private fun encodedLine(id: String, title: String, addedAtMs: Long): String {
        return listOf(
            encodeField(id),
            encodeField(title),
            addedAtMs.toString()
        ).joinToString(separator = "|")
    }

    private fun encodeField(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
