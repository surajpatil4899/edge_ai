package com.edgeai.assistant

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Records short "Okay Edge" samples for training a personal wake-word model
 * (prototype/train_wakeword.py). Samples go to app storage and to Download/EdgeAI/enroll so they can
 * be copied off the phone over plain USB file transfer.
 */
object Enrollment {
    const val TARGET = 30
    private const val SECONDS = 2.0f
    private const val TAG = "EdgeEnroll"

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "enroll").apply { mkdirs() }

    fun count(context: Context): Int = dir(context).listFiles { f -> f.extension == "wav" }?.size ?: 0

    fun notWakeDir(context: Context): File = File(dir(context), "not_wake").apply { mkdirs() }

    fun notWakeCount(context: Context): Int = notWakeDir(context).listFiles { f -> f.extension == "wav" }?.size ?: 0

    /** Phrases that must NOT wake the assistant; "Okay" alone matters most since "Edge" is spoken softly. */
    private val notWakePrompts = listOf(
        "Okay", "Okay, got it", "Hey Edge", "Okay", "Edge", "Okay, let's go", "Okay Google", "Okay",
        "What time is it", "Okay, fine", "Hello", "Okay, sure", "Set an alarm", "Okay", "Hey, how are you",
    )

    fun notWakePrompt(index: Int): String = notWakePrompts[index % notWakePrompts.size]

    /** What to vary for sample [index], so the model hears the phrase the way it will be used. */
    fun hint(index: Int): String = when {
        index < 10 -> "Normal voice, phone in your hand"
        index < 20 -> "Phone on the table, about an arm's length away"
        index < TARGET -> "Vary it: a bit faster, softer, or from across the room"
        else -> "Done. Extra samples still help"
    }

    @SuppressLint("MissingPermission") // the UI only offers this after RECORD_AUDIO is granted
    fun record(context: Context, notWake: Boolean = false, onDone: (Int) -> Unit) = thread(name = "enroll") {
        val app = context.applicationContext
        AssistantState.enrolling.value = true
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        val n = (VoicePipeline.SAMPLE_RATE * SECONDS).toInt()
        val pcm = ShortArray(n)
        var record: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(VoicePipeline.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, VoicePipeline.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, n * 2),
            )
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Thread.sleep(250) // let the beep finish so it isn't the loudest thing in the sample
            record.startRecording()
            var filled = 0
            while (filled < n) {
                val r = record.read(pcm, filled, minOf(1600, n - filled))
                if (r <= 0) break
                AssistantState.setLevel(FloatArray(r) { pcm[filled + it] / 32768f })
                filled += r
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = if (notWake) File(notWakeDir(app), "not_wake_$stamp.wav") else File(dir(app), "okay_edge_$stamp.wav")
            writeWav(file, pcm, filled)
            copyToDownloads(app, file, if (notWake) "enroll/not_wake" else "enroll", "audio/wav")
        } catch (e: Exception) {
            Log.e(TAG, "enrollment recording failed", e)
            AssistantState.info("Recording failed: ${e.message}")
        } finally {
            record?.run { stop(); release() }
            tone.release()
            AssistantState.setLevel(FloatArray(0))
            AssistantState.enrolling.value = false
            onDone(if (notWake) notWakeCount(app) else count(app))
        }
    }
}

fun writeWav(file: File, pcm: ShortArray, n: Int) {
    val rate = VoicePipeline.SAMPLE_RATE
    val dataBytes = n * 2
    fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    FileOutputStream(file).use { out ->
        out.write("RIFF".toByteArray() + le32(36 + dataBytes) + "WAVEfmt ".toByteArray() + le32(16) + le16(1) + le16(1) +
            le32(rate) + le32(rate * 2) + le16(2) + le16(16) + "data".toByteArray() + le32(dataBytes))
        val bytes = ByteArray(dataBytes)
        for (i in 0 until n) {
            bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        out.write(bytes)
    }
}

/** Copies [src] to Download/EdgeAI/[subdir], reachable over MTP file transfer without adb. */
fun copyToDownloads(context: Context, src: File, subdir: String, mime: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, src.name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/EdgeAI/$subdir")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
}
