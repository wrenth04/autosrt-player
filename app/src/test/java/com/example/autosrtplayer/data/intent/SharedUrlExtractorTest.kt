package com.example.autosrtplayer.data.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedUrlExtractorTest {
    @Test
    fun `extract m3u8 url from plain text`() {
        val url = "https://cdn.example.com/video/master.m3u8?token=abc"

        val result = SharedUrlExtractor.extractM3uUrl(url)

        assertEquals(url, result)
    }

    @Test
    fun `extract first m3u url from share sentence`() {
        val text = "請用 AutoSRT 播放 https://stream.example.com/movie/index.m3u8, 感謝"

        val result = SharedUrlExtractor.extractM3uUrl(text)

        assertEquals("https://stream.example.com/movie/index.m3u8", result)
    }

    @Test
    fun `return null when no m3u style url exists`() {
        val text = "https://example.com/video.mp4"

        val result = SharedUrlExtractor.extractM3uUrl(text)

        assertNull(result)
    }
}
