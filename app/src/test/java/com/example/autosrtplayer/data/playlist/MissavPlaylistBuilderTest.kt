package com.example.autosrtplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissavPlaylistBuilderTest {
    private val builder = MissavPlaylistBuilder()
    private val parser = PlaylistParser()

    @Test
    fun `builds playable m3u with required headers`() {
        val playlist = builder.build(
            id = "041820-001",
            detailUrl = "https://missav.ai/041820-001",
            mediaUrl = "https://surrit.com/0cd47383-6777-4781-86e8-8478e1661c09/1280x720/video.m3u8",
            title = "MyVideo",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        )

        assertTrue(playlist.startsWith("#EXTM3U"))
        assertTrue(playlist.contains("#EXTINF:-1,MyVideo"))
        assertTrue(playlist.contains("#EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
        assertTrue(playlist.contains("#EXTVLCOPT:http-referrer=https://missav.ai/"))
        assertTrue(playlist.trim().lines().last() == "https://surrit.com/0cd47383-6777-4781-86e8-8478e1661c09/1280x720/video.m3u8")
    }

    @Test
    fun `playlist parser reads generated headers and media url`() {
        val playlist = builder.build(
            id = "041820-001",
            detailUrl = "https://missav.ai/041820-001",
            mediaUrl = "https://surrit.com/0cd47383-6777-4781-86e8-8478e1661c09/1280x720/video.m3u8",
            title = "MyVideo",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        )

        val entry = parser.parse(playlist, "https://missav.ai/041820-001")
        assertEquals("MyVideo", entry.title)
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", entry.userAgent)
        assertEquals("https://missav.ai/", entry.referrer)
        assertEquals("https://surrit.com/0cd47383-6777-4781-86e8-8478e1661c09/1280x720/video.m3u8", entry.mediaUrl)
        assertEquals(null, entry.subtitleUrl)
    }
}
