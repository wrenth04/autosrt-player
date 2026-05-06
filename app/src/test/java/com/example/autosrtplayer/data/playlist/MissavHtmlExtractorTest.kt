package com.example.autosrtplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissavHtmlExtractorTest {
    private val extractor = MissavHtmlExtractor()

    @Test
    fun `select highest numbered source from packed script`() {
        val result = extractor.extract(
            """
            <html>
              <body>
                <script>
                  eval(function(p,a,c,k,e,d){return p;}('0 = "https://cdn.example.com/720/video.m3u8"; 1 = "https://cdn.example.com/1080/video.m3u8"; 2 = "https://cdn.example.com/master.m3u8";', 10, 3, 'source720|source1080|playlist'.split('|'), 0, {}))
                </script>
              </body>
            </html>
            """.trimIndent()
        )

        assertEquals("https://cdn.example.com/1080/video.m3u8", result.mediaUrl)
        assertEquals("https://cdn.example.com/720/video.m3u8", result.allMediaUrls["source720"])
        assertEquals("https://cdn.example.com/1080/video.m3u8", result.allMediaUrls["source1080"])
        assertEquals("https://cdn.example.com/master.m3u8", result.allMediaUrls["playlist"])
    }

    @Test
    fun `normalize relative urls from packed script`() {
        val result = extractor.extract(
            """
            <html>
              <body>
                <script>
                  eval(function(p,a,c,k,e,d){return p;}('0 = "/path/video.m3u8"; 1 = "//cdn.example.com/video.m3u8";', 10, 2, 'source|playlist'.split('|'), 0, {}))
                </script>
              </body>
            </html>
            """.trimIndent()
        )

        assertEquals("https://missav.ai/path/video.m3u8", result.mediaUrl)
        assertEquals("https://missav.ai/path/video.m3u8", result.allMediaUrls["source"])
        assertEquals("https://cdn.example.com/video.m3u8", result.allMediaUrls["playlist"])
    }

    @Test
    fun `extract direct urls from html when packed script is absent`() {
        val result = extractor.extract(
            """
            <html>
              <head><title>Sample Title</title></head>
              <body>
                <script>const playlist = "//cdn.example.com/video.m3u8";</script>
              </body>
            </html>
            """.trimIndent()
        )

        assertEquals("https://cdn.example.com/video.m3u8", result.mediaUrl)
        assertEquals("Sample Title", result.title)
        assertEquals("https://cdn.example.com/video.m3u8", result.allMediaUrls["playlist"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject html without m3u8`() {
        extractor.extract("<html><body><script>console.log('no video');</script></body></html>")
    }
}
