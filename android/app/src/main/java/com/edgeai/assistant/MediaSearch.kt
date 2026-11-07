package com.edgeai.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Play X" inside other apps. Tested on a Galaxy S25 (Nov 2026): YouTube's and YouTube Music's "play this"
 * activities and Spotify's only open search results or queue the wrong song, and Spotify ignores play requests from
 * apps it hasn't approved. What does start playback is opening a specific video, so [youtubeVideo] looks up the top
 * result first.
 */
object MediaSearch {
    // The mobile page (what a phone gets) escapes its quotes: \x22videoId\x22:\x22<id>; the desktop one doesn't.
    private val VIDEO_ID = Regex("""(?:"videoId":"|\\x22videoId\\x22:\\x22)([A-Za-z0-9_-]{11})""")

    /**
     * The top YouTube result's video id for [query], or null (offline, blocked, page changed). This is the app's
     * only network request, made only when the user asks to play something on YouTube or YouTube Music; it sends
     * the same query the YouTube app would.
     */
    fun youtubeVideo(query: String, timeoutMs: Int = 4000): String? = runCatching {
        val conn = URL(DeepLinks.youtubeSearch(query)).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept-Language", "en")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
        conn.inputStream.bufferedReader().use { r ->
            // The first videoId in the page's data is the top result; stop reading once found.
            r.lineSequence().firstNotNullOfOrNull { VIDEO_ID.find(it)?.groupValues?.get(1) }
        }
    }.onFailure { AppLogs.activity("YouTube lookup failed: ${it.javaClass.simpleName}") }.getOrNull()

    /** Asks [pkg]'s media browser service to play [query] (apps that allow it); true if the request was sent. */
    fun viaBrowser(context: Context, pkg: String, query: String, timeoutMs: Long = 3000): Boolean {
        val service = context.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return false
        val app = context.applicationContext
        val done = CountDownLatch(1)
        var accepted = false
        var browser: MediaBrowser? = null
        val main = Handler(Looper.getMainLooper())
        main.post {
            browser = MediaBrowser(app, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    accepted = runCatching {
                        MediaController(app, browser!!.sessionToken).transportControls.playFromSearch(query, Bundle())
                    }.isSuccess
                    done.countDown()
                }

                override fun onConnectionFailed() = done.countDown()
                override fun onConnectionSuspended() = done.countDown()
            }, null).also { it.connect() }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        // Stay connected a moment so the request is delivered before the binding goes away.
        main.postDelayed({ browser?.disconnect() }, 5000)
        AppLogs.activity("media browser $pkg: ${if (accepted) "play request sent" else "not available"}")
        return accepted
    }
}
