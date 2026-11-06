package com.edgeai.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Spoken replies through Android's TextToSpeech (on-device engine). Text spoken before init is queued. */
object Speaker {
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
        init(context)
        if (ready) speakNow(text) else pending += text
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edge-${System.nanoTime()}")
    }
}
