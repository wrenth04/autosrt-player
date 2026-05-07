package com.example.autosrtplayer.data.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class FavoriteCodecTest {
    @Test
    fun `decode empty or malformed data returns empty list`() {
        assertTrue(FavoriteCodec.decode(null).isEmpty())
        assertTrue(FavoriteCodec.decode("").isEmpty())
        assertTrue(FavoriteCodec.decode("not valid").isEmpty())
    }

    @Test
    fun `encode and decode preserve ids`() {
        val items = listOf(
            FavoriteItem(id = "ABCD-123"),
            FavoriteItem(id = "EFGH-456")
        )

        val decoded = FavoriteCodec.decode(FavoriteCodec.encode(items))

        assertEquals(items, decoded)
    }

    @Test
    fun `decode skips blank ids and deduplicates ids case insensitively`() {
        val decoded = FavoriteCodec.decode(
            listOf(
                encodedLine(" ABCD-123 "),
                encodedLine(""),
                encodedLine("abcd-123"),
                encodedLine("WXYZ-999")
            ).joinToString(separator = "\n")
        )

        assertEquals(2, decoded.size)
        assertEquals("ABCD-123", decoded[0].id)
        assertEquals("WXYZ-999", decoded[1].id)
    }

    private fun encodedLine(id: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(id.toByteArray(StandardCharsets.UTF_8))
    }
}
