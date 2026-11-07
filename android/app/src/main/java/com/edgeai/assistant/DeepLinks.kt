package com.edgeai.assistant

import java.net.URLEncoder

/**
 * Links that open another app already doing something ("open YouTube and play X" -> YouTube's search results).
 * Android does not let one app drive another's screen; these public links and intents are how assistants reach
 * inside apps. Pure (plain strings), for unit tests; [ActionExecutor] launches them.
 */
object DeepLinks {
    enum class MediaApp(val pkg: String, val label: String) {
        YOUTUBE("com.google.android.youtube", "YouTube"),
        YOUTUBE_MUSIC("com.google.android.apps.youtube.music", "YouTube Music"),
        SPOTIFY("com.spotify.music", "Spotify"),
        JIOSAAVN("com.jio.media.jiobeats", "JioSaavn"),
        GAANA("com.gaana", "Gaana"),
        WYNK("com.bsbportal.music", "Wynk Music"),
        AMAZON_MUSIC("com.amazon.mp3", "Amazon Music"),
    }

    enum class ChatApp(val pkg: String, val label: String) {
        WHATSAPP("com.whatsapp", "WhatsApp"),
        WHATSAPP_BUSINESS("com.whatsapp.w4b", "WhatsApp Business"),
        TELEGRAM("org.telegram.messenger", "Telegram"),
        SIGNAL("org.thoughtcrime.securesms", "Signal"),
    }

    const val MAPS = "com.google.android.apps.maps"
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val PLAY_STORE = "com.android.vending"

    /** When the user names no app ("play Kesariya"): the ones we can start playback in, then YouTube in the browser. */
    val PLAY_FROM_SEARCH_ORDER = listOf(MediaApp.YOUTUBE_MUSIC, MediaApp.YOUTUBE)

    private val mediaNames = mapOf(
        "YouTube" to MediaApp.YOUTUBE, "YouTube Music" to MediaApp.YOUTUBE_MUSIC, "YT Music" to MediaApp.YOUTUBE_MUSIC,
        "Spotify" to MediaApp.SPOTIFY, "JioSaavn" to MediaApp.JIOSAAVN, "Saavn" to MediaApp.JIOSAAVN, "Gaana" to MediaApp.GAANA,
        "Wynk" to MediaApp.WYNK, "Wynk Music" to MediaApp.WYNK, "Amazon Music" to MediaApp.AMAZON_MUSIC,
    )
    private val chatNames = mapOf(
        "WhatsApp" to ChatApp.WHATSAPP, "WhatsApp Business" to ChatApp.WHATSAPP_BUSINESS, "Telegram" to ChatApp.TELEGRAM,
        "Signal" to ChatApp.SIGNAL,
    )

    /** "you tube", "YT music", "spotify" -> the app; null for an app this file has no links for. */
    fun mediaApp(spoken: String): MediaApp? = ContactMatcher.best(spoken, mediaNames.keys)?.let(mediaNames::getValue)

    /** "what's app", "whatsapp" -> WhatsApp, etc. */
    fun chatApp(spoken: String): ChatApp? = ContactMatcher.best(spoken, chatNames.keys)?.let(chatNames::getValue)

    fun youtubeSearch(query: String) = "https://www.youtube.com/results?search_query=${enc(query)}"
    fun youtubeMusicSearch(query: String) = "https://music.youtube.com/search?q=${enc(query)}"
    fun youtubeWatch(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
    fun youtubeMusicWatch(videoId: String) = "https://music.youtube.com/watch?v=$videoId"
    fun spotifySearch(query: String) = "spotify:search:${enc(query)}"
    fun googleSearch(query: String) = "https://www.google.com/search?q=${enc(query)}"
    fun navigation(destination: String) = "google.navigation:q=${enc(destination)}"
    fun mapSearch(query: String) = "geo:0,0?q=${enc(query)}"
    fun playStoreSearch(query: String) = "market://search?q=${enc(query)}&c=apps"
    fun playStoreWebSearch(query: String) = "https://play.google.com/store/search?q=${enc(query)}&c=apps"

    /** WhatsApp's click-to-chat link: the chat opens with [message] typed; the user taps send. */
    fun whatsApp(internationalNumber: String, message: String?) =
        "https://wa.me/$internationalNumber" + (message?.takeIf { it.isNotBlank() }?.let { "?text=${enc(it)}" } ?: "")

    /** Country -> (calling code, usual national number length). */
    private val countries = mapOf(
        "in" to ("91" to 10), "us" to ("1" to 10), "ca" to ("1" to 10), "gb" to ("44" to 10), "ae" to ("971" to 9),
        "au" to ("61" to 9), "sg" to ("65" to 8), "de" to ("49" to 11), "fr" to ("33" to 9), "np" to ("977" to 10),
        "bd" to ("880" to 10), "lk" to ("94" to 9), "pk" to ("92" to 10), "sa" to ("966" to 9), "qa" to ("974" to 8),
        "nz" to ("64" to 9),
    )

    /**
     * A contact's number as wa.me wants it: country code and digits, no "+". Numbers saved without a country code
     * ("98765 43210", "098765 43210") get the SIM's ([countryIso], e.g. "in"); null if that is unknown.
     */
    fun whatsAppNumber(saved: String, countryIso: String?): String? {
        val digits = saved.filter { it.isDigit() }
        if (digits.length < 6) return null
        return when {
            saved.trim().startsWith("+") -> digits
            saved.trim().startsWith("00") -> digits.drop(2)
            else -> {
                val (code, nationalLength) = countries[countryIso?.lowercase()] ?: return null
                // "919876543210" was saved with its code but no "+"; "9876543210" and trunk "09876543210" were not.
                if (digits.startsWith(code) && digits.length > nationalLength) digits else code + digits.trimStart('0')
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")
}
