package com.example.autosrtplayer.data.todayhot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayHotRepositoryTest {
    private val repository = TodayHotRepository()

    @Test
    fun `parse today hot feed and derive cover url from code`() {
        val feed = repository.parse(
            """
            {
              "total": 2,
              "source": "cache",
              "fetched_at": "2026-05-07T00:00:00Z",
              "items": [
                {
                  "code": " MIMK-267 ",
                  "title": "First item",
                  "cover_url": null,
                  "updated_at": null,
                  "url": "https://missav.ai/mimk-267"
                },
                {
                  "code": "MIDA-624",
                  "title": "Second item",
                  "cover_url": "",
                  "updated_at": "2026-05-07 01:02:03",
                  "url": "https://missav.ai/mida-624"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, feed.total)
        assertEquals(2, feed.items.size)
        assertEquals("MIMK-267", feed.items[0].code)
        assertEquals("First item", feed.items[0].title)
        assertEquals("https://fourhoi.com/mimk-267/cover-n.jpg", feed.items[0].fourHoiCoverUrl)
        assertEquals("MIDA-624", feed.items[1].code)
        assertEquals("https://fourhoi.com/mida-624/cover-n.jpg", feed.items[1].fourHoiCoverUrl)
        assertEquals("2026-05-07 01:02:03", feed.items[1].updatedAt)
    }

    @Test
    fun `skip empty code items`() {
        val feed = repository.parse(
            """
            {
              "total": 2,
              "items": [
                { "code": "   ", "title": "Ignored", "url": "https://example.com" },
                { "code": "ABCD-123", "title": "Kept", "url": "https://example.com/2" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, feed.items.size)
        assertTrue(feed.items.first().code.isNotBlank())
        assertEquals("ABCD-123", feed.items.first().code)
    }
}
