package com.music.player.sources

import com.music.player.sources.siriusxm.SiriusXmParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ArtworkUrlsTest {
    @Test
    fun `sizes round up to a few steps`() {
        assertEquals(128, ArtworkUrls.step(126))
        assertEquals(1024, ArtworkUrls.step(1020))
        assertEquals(1024, ArtworkUrls.step(3000))
    }

    @Test
    fun `jellyfin artwork is asked for at the size shown`() {
        val saved = "https://jf.example.com/Items/abc/Images/Primary?maxHeight=512&tag=t1"
        assertEquals("https://jf.example.com/Items/abc/Images/Primary?tag=t1&maxHeight=1024&maxWidth=1024", ArtworkUrls.sized(saved, 1000))
        assertEquals("https://jf.example.com/Items/abc/Images/Primary?tag=t1&maxHeight=128&maxWidth=128", ArtworkUrls.sized(saved, 126))
        assertEquals(
            "https://jf.example.com/Items/abc/Images/Primary?maxHeight=256&maxWidth=256",
            ArtworkUrls.sized("https://jf.example.com/Items/abc/Images/Primary", 200),
        )
    }

    @Test
    fun `siriusxm and youtube music artwork are resized, anything else left alone`() {
        val sxm = SiriusXmParser.imageUrl("abc/def.jpg", 300)
        val resized = ArtworkUrls.sized(sxm, 1000)
        val params = String(Base64.getDecoder().decode(resized.removePrefix(SiriusXmParser.IMAGE_BASE)))
        assertTrue(params, params.contains("\"width\":1024") && params.contains("\"key\":\"abc/def.jpg\"") && params.contains("jpeg"))

        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj",
            ArtworkUrls.sized("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", 900),
        )
        assertEquals("https://example.com/cover.jpg", ArtworkUrls.sized("https://example.com/cover.jpg", 900))
    }
}
