package com.edgeai.assistant

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings, kept across restarts. Read anywhere; change with [set]. */
object Prefs {
    class Pref internal constructor(val key: String, default: Boolean) {
        internal val flow = MutableStateFlow(default)
        val state: StateFlow<Boolean> = flow.asStateFlow()
        val value get() = flow.value
    }

    /** The welcome and permission steps were completed. */
    val onboarded = Pref("onboarded", false)

    /** Speak replies aloud (they are always shown on screen). */
    val spokenReplies = Pref("spoken_replies", true)

    /** Listen for "Okay Edge"; off = only the mic button starts a command. */
    val wakeWord = Pref("wake_word", true)

    /** Show the model's raw output, timings and guard decisions under each reply (for testers and debugging). */
    val showDetails = Pref("show_details", false)

    private val all = listOf(onboarded, spokenReplies, wakeWord, showDetails)
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp != null) return
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        all.forEach { it.flow.value = p.getBoolean(it.key, it.flow.value) }
        sp = p
    }

    fun set(pref: Pref, value: Boolean) {
        pref.flow.value = value
        sp?.edit()?.putBoolean(pref.key, value)?.apply()
    }
}
