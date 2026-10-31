package com.edgeai.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** 16 kHz mono 16-bit PCM source. [read] returns 0 when no audio is available yet. */
interface AudioInput : AutoCloseable {
    fun read(buffer: ShortArray): Int
}

class MicInput : AudioInput {
    private val record: AudioRecord

    init {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 2 s of headroom so audio isn't dropped while ASR decodes on the reading thread.
        @SuppressLint("MissingPermission") // checked by ListenerService before construction
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE * 2 * 2),
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone" }
        r.startRecording()
        record = r
    }

    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size).coerceAtLeast(0)

    override fun close() {
        record.stop()
        record.release()
    }
}

/**
 * Debug-only: PCM streamed from the laptop by prototype/mic_bridge.py over `adb forward`.
 * Bypasses the emulator's audio input, which stops delivering audio after the mic is reopened.
 */
class BridgeInput(private val onStatus: (String) -> Unit) : AudioInput {
    private val server = ServerSocket().apply {
        reuseAddress = true // a quick Stop/Start must not fail while the old socket lingers
        soTimeout = 500
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 1)
    }
    private var client: Socket? = null
    private var stream: InputStream? = null
    private val bytes = ByteArray(SAMPLE_RATE / 5)
    private var leftover = -1 // dangling low byte when a read splits a sample

    init {
        onStatus("Waiting for laptop mic bridge on port $PORT")
    }

    override fun read(buffer: ShortArray): Int {
        val input = stream ?: accept() ?: return 0
        val n = try {
            input.read(bytes, 0, minOf(bytes.size, buffer.size * 2))
        } catch (_: SocketTimeoutException) {
            return 0
        }
        if (n < 0) {
            disconnect()
            onStatus("Laptop mic bridge disconnected")
            return 0
        }
        var count = 0
        var i = 0
        if (leftover >= 0 && n > 0) {
            buffer[count++] = ((bytes[0].toInt() shl 8) or leftover).toShort()
            leftover = -1
            i = 1
        }
        while (i + 1 < n) {
            buffer[count++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort()
            i += 2
        }
        if (i < n) leftover = bytes[i].toInt() and 0xff
        return count
    }

    private fun accept(): InputStream? = try {
        server.accept().also { s ->
            s.soTimeout = 500
            client = s
            stream = s.getInputStream()
            onStatus("Laptop mic bridge connected")
        }.getInputStream()
    } catch (_: SocketTimeoutException) {
        null
    }

    private fun disconnect() {
        client?.close()
        client = null
        stream = null
        leftover = -1
    }

    override fun close() {
        disconnect()
        server.close()
    }

    companion object {
        const val PORT = 8765
    }
}

private const val SAMPLE_RATE = VoicePipeline.SAMPLE_RATE
