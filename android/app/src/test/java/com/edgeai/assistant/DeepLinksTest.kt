package com.edgeai.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    @Test
    fun appsAsHeard() {
        assertEquals(DeepLinks.MediaApp.YOUTUBE, DeepLinks.mediaApp("you tube"))
        assertEquals(DeepLinks.MediaApp.YOUTUBE_MUSIC, DeepLinks.mediaApp("YT music"))
        assertEquals(DeepLinks.MediaApp.SPOTIFY, DeepLinks.mediaApp("spotify"))
        assertNull(DeepLinks.mediaApp("Hotstar"))
        assertEquals(DeepLinks.ChatApp.WHATSAPP, DeepLinks.chatApp("what's app"))
        assertEquals(DeepLinks.ChatApp.TELEGRAM, DeepLinks.chatApp("telegram"))
    }

    @Test
    fun links() {
        assertEquals("https://www.youtube.com/results?search_query=bollywood%20songs", DeepLinks.youtubeSearch("bollywood songs"))
        assertEquals("google.navigation:q=the%20airport", DeepLinks.navigation("the airport"))
        assertEquals("market://search?q=Swiggy&c=apps", DeepLinks.playStoreSearch("Swiggy"))
        assertEquals("https://wa.me/919876543210?text=I%27m%20late", DeepLinks.whatsApp("919876543210", "I'm late"))
        assertEquals("https://wa.me/919876543210", DeepLinks.whatsApp("919876543210", null))
    }

    @Test
    fun whatsAppNumbers() {
        assertEquals("919876543210", DeepLinks.whatsAppNumber("98765 43210", "in"))
        assertEquals("919876543210", DeepLinks.whatsAppNumber("098765 43210", "in"))
        assertEquals("919876543210", DeepLinks.whatsAppNumber("+91 98765 43210", null))
        assertEquals("919876543210", DeepLinks.whatsAppNumber("91 98765 43210", "in"))
        assertEquals("447700900123", DeepLinks.whatsAppNumber("0044 7700 900123", "in"))
        assertNull(DeepLinks.whatsAppNumber("98765 43210", null))   // no country code known
    }
}
