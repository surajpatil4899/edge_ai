package com.edgeai.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Spoken replies through Android's TextToSpeech (on-device engine). Text spoken before init is queued.
 * Silent when the user turned spoken replies off ([Prefs.spokenReplies]).
 */
object Speaker {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = mutableListOf<String>()

    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault().takeIf { tts?.isLanguageAvailable(it) ?: -1 >= 0 } ?: Locale.US
                    tts?.setSpeechRate(1.05f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { _speaking.value = true }
                        override fun onDone(id: String?) { _speaking.value = false }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { _speaking.value = false }
                        override fun onStop(id: String?, interrupted: Boolean) { _speaking.value = false }
                    })
                    pending.forEach { speakNow(it) }
                } else {
                    Log.w("EdgeSpeaker", "TextToSpeech init failed: $status")
                }
                pending.clear()
            }
        }
    }

    @Synchronized
    fun speak(context: Context, text: String) {
        if (!Prefs.spokenReplies.value) return
        init(context)
        if (ready) speakNow(text) else pending += text
    }

    /** Cuts the current reply short (the user tapped the mic to say something else). */
    fun stop() {
        tts?.stop()
        _speaking.value = false
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edge-${System.nanoTime()}")
    }
}
