package com.edgeai.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

enum class Status { Stopped, Loading, WaitingForWake, Listening, Transcribing, Error }

sealed interface LogEntry {
    val time: Long

    data class Wake(override val time: Long, val keyword: String) : LogEntry
    data class Command(override val time: Long, val text: String, val audioSeconds: Float, val asrMillis: Long) : LogEntry
    data class Info(override val time: Long, val message: String) : LogEntry
}

/** Shared between the listening service (writer) and the UI (reader). */
object AssistantState {
    private val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** Set by the UI's "tap to speak" button; consumed by the pipeline on the audio thread. */
    val manualTrigger = AtomicBoolean(false)

    /** Debug builds: read audio from prototype/mic_bridge.py instead of the device mic. */
    val useBridge = MutableStateFlow(false)

    /** Debug builds: save everything heard to a wav on the device, for offline tuning. */
    val recordSession = MutableStateFlow(false)

    /** True while an enrollment sample is being recorded (mic is busy). */
    val enrolling = MutableStateFlow(false)
    val enrollCount = MutableStateFlow(0)
    val notWakeCount = MutableStateFlow(0)

    /** Input loudness of the latest chunk, 0..1, for the UI level bar. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val session = AtomicInteger(0)

    fun newSession(): Int = session.incrementAndGet()

    fun isCurrent(id: Int): Boolean = session.get() == id

    fun setLevel(samples: FloatArray) {
        if (samples.isEmpty()) {
            _level.value = 0f
            return
        }
        var peak = 0f
        for (x in samples) peak = maxOf(peak, abs(x))
        // Speech peaks sit around 0.1-0.5; a sqrt curve makes quiet speech visible.
        _level.value = sqrt(peak).coerceAtMost(1f)
    }

    fun setStatus(status: Status) {
        _status.value = status
    }

    fun add(entry: LogEntry) {
        _log.update { (listOf(entry) + it).take(100) }
    }

    fun info(message: String) = add(LogEntry.Info(System.currentTimeMillis(), message))
}
