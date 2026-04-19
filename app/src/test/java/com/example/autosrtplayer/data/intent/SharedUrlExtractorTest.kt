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

    @Test
    fun `extract source id from app scheme url`() {
        val result = SharedUrlExtractor.extractLaunchTarget("autosrt-player://ABCD-123")

        assertEquals(LaunchTarget.SourceId("ABCD-123"), result)
    }

    @Test
    fun `extract m3u8 url from app scheme url`() {
        val result = SharedUrlExtractor.extractLaunchTarget(
            "autosrt-player://https://stream.example.com/path/index.m3u8"
        )

        assertEquals(
            LaunchTarget.Url("https://stream.example.com/path/index.m3u8"),
            result
        )
    }

    @Test
    fun `extract encoded m3u8 url from app scheme url`() {
        val result = SharedUrlExtractor.extractLaunchTarget(
            "autosrt-player://https%3A%2F%2Fstream.example.com%2Fpath%2Findex.m3u8"
        )

        assertEquals(
            LaunchTarget.Url("https://stream.example.com/path/index.m3u8"),
            result
        )
    }

    @Test
    fun `extract m3u8 url from app scheme url without colon after https`() {
        val result = SharedUrlExtractor.extractLaunchTarget(
            "autosrt-player://https//github.com/wrenth04/autosrt/releases/download/srt/miad-791.m3u8"
        )

        assertEquals(
            LaunchTarget.Url("https://github.com/wrenth04/autosrt/releases/download/srt/miad-791.m3u8"),
            result
        )
    }

    @Test
    fun `extract m3u8 url from app scheme url without protocol`() {
        val result = SharedUrlExtractor.extractLaunchTarget(
            "autosrt-player://github.com/wrenth04/autosrt/releases/download/srt/miad-791.m3u8"
        )

        assertEquals(
            LaunchTarget.Url("https://github.com/wrenth04/autosrt/releases/download/srt/miad-791.m3u8"),
            result
        )
    }
}
