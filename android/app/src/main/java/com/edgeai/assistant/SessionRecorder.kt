package com.edgeai.assistant

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug aid: writes everything the pipeline hears to session_<time>.wav (16 kHz mono PCM16) plus
 * session_<time>.txt with pipeline events at their audio offsets, so the same audio can be replayed
 * through prototype/listen.py to tune the wake word and compare ASR models.
 */
class SessionRecorder(dir: File, private val onClosed: (wav: File, events: File) -> Unit = { _, _ -> }) : AutoCloseable {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val wav = File(dir.apply { mkdirs() }, "session_$stamp.wav")
    private val out = RandomAccessFile(wav, "rw").apply { setLength(0); write(ByteArray(HEADER)) }
    private val eventsFile = File(dir, "session_$stamp.txt")
    private val events = eventsFile.bufferedWriter()
    private val bytes = ByteArray(VoicePipeline.SAMPLE_RATE / 5 * 2)
    private var samples = 0L

    fun write(pcm: ShortArray, n: Int) {
        val buf = if (n * 2 <= bytes.size) bytes else ByteArray(n * 2)
        for (i in 0 until n) {
            buf[2 * i] = (pcm[i].toInt() and 0xff).toByte()
            buf[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        out.write(buf, 0, n * 2)
        samples += n
        // Rewrite the header every ~5 s so the file stays readable if the app is killed mid-session.
        if (samples % (VoicePipeline.SAMPLE_RATE * 5L) < n) {
            val end = out.filePointer
            out.seek(0)
            out.write(header(samples * 2))
            out.seek(end)
        }
    }

    fun event(text: String) {
        events.write("%.2f\t%s\n".format(Locale.US, samples / VoicePipeline.SAMPLE_RATE.toFloat(), text))
        events.flush()
    }

    override fun close() {
        val dataBytes = samples * 2
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
        events.close()
        onClosed(wav, eventsFile)
    }

    private fun header(dataBytes: Long): ByteArray {
        val rate = VoicePipeline.SAMPLE_RATE
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return "RIFF".toByteArray() + le32(36 + dataBytes) + "WAVEfmt ".toByteArray() + le32(16) + le16(1) + le16(1) +
            le32(rate.toLong()) + le32(rate * 2L) + le16(2) + le16(16) + "data".toByteArray() + le32(dataBytes)
    }

    private companion object {
        const val HEADER = 44
    }
}
