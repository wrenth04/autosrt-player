package com.example.autosrtplayer.data.playlist

import com.example.autosrtplayer.data.playback.MediaItemBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistParserTest {
    private val parser = PlaylistParser()
    private val mediaItemBuilder = MediaItemBuilder()

    @Test
    fun `parse playlist with headers and subtitle`() {
        val entry = parser.parse(
            content = """
            #EXTM3U
            #EXTINF:-1,MyVideo
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-referrer=https://missav.ai/
            #EXTSUB:https://example.com/subtitle.srt
            https://example.com/video.m3u8
            """.trimIndent(),
            playlistUrl = "https://example.com/list.m3u8"
        )

        assertEquals("MyVideo", entry.title)
        assertEquals("Mozilla/5.0", entry.userAgent)
        assertEquals("https://missav.ai/", entry.referrer)
        assertEquals("https://example.com/subtitle.srt", entry.subtitleUrl)
        assertEquals("https://example.com/video.m3u8", entry.mediaUrl)
    }

    @Test
    fun `derive subtitle from m3u8 playlist url when extsub missing`() {
        val entry = parser.parse(
            content = """
            #EXTM3U
            #EXTINF:-1,MyVideo
            https://example.com/video.m3u8
            """.trimIndent(),
            playlistUrl = "https://example.com/list.m3u8"
        )

        assertEquals("https://example.com/list.srt", entry.subtitleUrl)
    }

    @Test
    fun `derive subtitle from m3u playlist url when extsub missing`() {
        val entry = parser.parse(
            content = """
            #EXTM3U
            #EXTINF:-1,MyVideo
            https://example.com/video.m3u8
            """.trimIndent(),
            playlistUrl = "https://example.com/list.m3u"
        )

        assertEquals("https://example.com/list.srt", entry.subtitleUrl)
    }

    @Test
    fun `do not derive subtitle when playlist url is not m3u or m3u8`() {
        val entry = parser.parse(
            content = """
            #EXTM3U
            #EXTINF:-1,MyVideo
            https://example.com/video.m3u8
            """.trimIndent(),
            playlistUrl = "https://example.com/list.txt"
        )

        assertNull(entry.subtitleUrl)
    }

    @Test
    fun `parse playlist without subtitle and without playlist url`() {
        val entry = parser.parse(
            """
            #EXTM3U
            #EXTINF:-1,MyVideo
            https://example.com/video.m3u8
            """.trimIndent()
        )

        assertNull(entry.subtitleUrl)
        assertEquals("https://example.com/video.m3u8", entry.mediaUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject playlist without media url`() {
        parser.parse(
            """
            #EXTM3U
            #EXTINF:-1,MyVideo
            """.trimIndent()
        )
    }

    @Test
    fun `infer subtitle mime type`() {
        assertEquals(
            androidx.media3.common.MimeTypes.APPLICATION_SUBRIP,
            mediaItemBuilder.inferSubtitleMimeType("https://example.com/a.srt")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.TEXT_VTT,
            mediaItemBuilder.inferSubtitleMimeType("https://example.com/a.vtt?token=1")
        )
        assertNull(mediaItemBuilder.inferSubtitleMimeType("https://example.com/a.ass"))
    }
}
