package com.edgeai.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatcherTest {
    private val book = listOf("Mummy", "Papa", "Rahul Sharma", "Rahul Office", "Priya K", "Sanket Patil", "Didi", "Amit", "Dr Mehta Clinic")

    @Test
    fun relations() {
        assertEquals("Mummy", ContactMatcher.best("mom", book))
        assertEquals("Mummy", ContactMatcher.best("my mom", book))
        assertEquals("Papa", ContactMatcher.best("dad", book))
        assertEquals("Didi", ContactMatcher.best("sister", book))
    }

    @Test
    fun namesAndTypos() {
        assertEquals("Sanket Patil", ContactMatcher.best("Sanket", book))
        assertEquals("Priya K", ContactMatcher.best("Priya", book))
        assertEquals("Rahul Sharma", ContactMatcher.best("Rahul Sharma", book))
        assertEquals("Amit", ContactMatcher.best("Ameet", book))
        assertEquals("Sanket Patil", ContactMatcher.best("Sankit", book))
    }

    @Test
    fun appLabelsIgnoringSpaces() {
        val apps = listOf("WhatsApp", "YouTube", "YT Music", "Spotify", "Settings", "Chrome")
        assertEquals("WhatsApp", ContactMatcher.best("what's up", apps)) // ASR's spelling of "WhatsApp"
        assertEquals("YouTube", ContactMatcher.best("you tube", apps))
        assertEquals("Spotify", ContactMatcher.best("spotify", apps))
    }

    @Test
    fun noGoodMatch() {
        assertNull(ContactMatcher.best("Zoya", book))
        assertNull(ContactMatcher.best("", book))
    }

    @Test
    fun numbers() {
        assertEquals("9876543210", ContactMatcher.asNumber("98765 43210"))
        assertEquals("112", ContactMatcher.asNumber("112"))
        assertNull(ContactMatcher.asNumber("Rahul"))
    }
}
