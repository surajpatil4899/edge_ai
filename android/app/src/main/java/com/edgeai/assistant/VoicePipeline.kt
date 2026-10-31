package com.edgeai.assistant

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

sealed interface PipelineEvent {
    data class Wake(val keyword: String) : PipelineEvent
    data object Transcribing : PipelineEvent
    data class Command(val text: String, val heard: String, val audioSeconds: Float, val asrMillis: Long) : PipelineEvent

    /** An utterance with no command in it (wake-word tail, the beep, "um"); still listening. */
    data class Ignored(val heard: String) : PipelineEvent
    data object Timeout : PipelineEvent
}

// ASR renders "Okay/Hey Edge" many ways ("He edge", "Hey it", "Hey, Ed.", "Hedge"); strip them only at the start.
private val WAKE_PREFIX = Regex(
    "^\\W*(?:(?:hey|hay|hi|he|a|okay|ok)\\W+(?:edge|edges|edj|edg|ej|aj|hedge|it|ed|age|etch|edit)|edge|edges|edj|edg|hedge)\\b\\W*",
    RegexOption.IGNORE_CASE,
)
private val WORD = Regex("[A-Za-z0-9']+")
private val FILLER = setOf("um", "uh", "mm", "mhm", "hmm", "ah", "oh", "huh", "hey", "he", "okay", "ok", "edge", "ed", "it", "the", "a")

/** Removes a leading "Okay Edge" from a transcript; returns "" when nothing command-like remains. */
internal fun commandText(heard: String): String {
    val text = WAKE_PREFIX.replace(heard.trim(), "").trim().trimEnd('.', ',', '!', '?')
    val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
    return if (words.isEmpty() || words.all { it in FILLER }) "" else text
}

/**
 * Wake word -> VAD-bounded command -> Moonshine ASR. Port of prototype/listen.py, which is where
 * the tuning (boost/threshold, pre-roll, segment padding) was measured. Not thread-safe: call
 * [feed] from the audio thread only.
 */
class VoicePipeline(assets: AssetManager, private val emit: (PipelineEvent) -> Unit) {

    private val kws = KeywordSpotter(
        assets,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$KWS_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$KWS_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "$KWS_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "$KWS_DIR/tokens.txt",
                numThreads = 1,
                modelType = "zipformer2",
            ),
            keywordsFile = "keywords.txt",
            keywordsScore = 3.0f,
            keywordsThreshold = 0.05f,
        ),
    )

    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "models/vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = END_SILENCE_SECONDS,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = MAX_COMMAND_SECONDS,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private val asr = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "models/asr/encoder_model.ort",
                    mergedDecoder = "models/asr/decoder_model_merged.ort",
                ),
                tokens = "models/asr/tokens.txt",
                numThreads = 2,
            ),
        ),
    )

    /** Personal wake model (prototype/train_wakeword.py) when its weights are bundled; otherwise the spotter. */
    private val wakeModel: WakeWordModel? =
        if (assets.list("")?.contains("wakeword.json") == true) WakeWordModel(assets) else null
    val usingPersonalWakeModel get() = wakeModel != null
    private var wakeQuietUntil = 0L

    private var stream: OnlineStream = kws.createStream()
    private var kwsFed = 0L // samples fed to the current spotter stream; its timestamps count from 0
    private val history = RollingAudio(HISTORY_SECONDS * SAMPLE_RATE)

    private var listening = false
    private var listenStartedAt = 0L // resets after an ignored utterance, giving the user more time
    private var listenFirstStartedAt = 0L
    private var heardSpeech = false
    private var clock = 0L
    private val vadWindow = FloatArray(VAD_WINDOW)
    private var vadWindowFill = 0
    private val commandAudio = RollingAudio(30 * SAMPLE_RATE)

    fun feed(samples: FloatArray) {
        clock += samples.size
        if (!listening && AssistantState.manualTrigger.getAndSet(false)) {
            emit(PipelineEvent.Wake("manual"))
            startListening(FloatArray(0))
        }
        if (wakeModel != null) {
            // Fed even while listening so its 1.3 s window never holds stale audio that could re-fire.
            val detected = wakeModel.accept(samples)
            history.append(samples)
            if (!listening && detected && clock >= wakeQuietUntil) {
                emit(PipelineEvent.Wake("OKAY_EDGE"))
                // Fires within ~0.1-0.3 s of the phrase ending; replay a little so a no-pause command keeps its
                // first word (any "Edge" tail is stripped later by commandText).
                startListening(history.tail(minOf(history.size, PREROLL_SAMPLES)))
                return
            }
        }
        if (listening) feedVad(samples) else if (wakeModel == null) spot(samples)
    }

    private fun spot(samples: FloatArray) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        kwsFed += samples.size
        history.append(samples)
        while (kws.isReady(stream)) {
            kws.decode(stream)
            // Read once: each getResult call fetches (and clears) the native result.
            val hit = kws.getResult(stream)
            if (hit.keyword.isNotBlank()) {
                emit(PipelineEvent.Wake(hit.keyword.trim()))
                startListening(audioAfterKeyword(hit.timestamps))
                return
            }
        }
    }

    /** The spotter fires ~0.5 s after the phrase ends; replay that audio so the command isn't clipped. */
    private fun audioAfterKeyword(timestamps: FloatArray): FloatArray {
        val endSeconds = if (timestamps.isNotEmpty()) timestamps.last() + 0.12f else kwsFed / SAMPLE_RATE.toFloat() - 0.5f
        val back = (kwsFed - (endSeconds * SAMPLE_RATE).toLong()).toInt()
        return if (back in 1..history.size) history.tail(back) else FloatArray(0)
    }

    private fun startListening(preroll: FloatArray) {
        listening = true
        heardSpeech = false
        listenStartedAt = clock
        listenFirstStartedAt = clock
        vad.reset()
        vadWindowFill = 0
        commandAudio.clear()
        feedVad(preroll)
    }

    private fun feedVad(samples: FloatArray) {
        commandAudio.append(samples)
        var i = 0
        while (i < samples.size) {
            val n = minOf(VAD_WINDOW - vadWindowFill, samples.size - i)
            System.arraycopy(samples, i, vadWindow, vadWindowFill, n)
            vadWindowFill += n
            i += n
            if (vadWindowFill == VAD_WINDOW) {
                vad.acceptWaveform(vadWindow.copyOf())
                vadWindowFill = 0
            }
        }
        if (vad.isSpeechDetected()) heardSpeech = true

        while (!vad.empty()) {
            val audio = popSegment()
            val (heard, millis) = transcribe(audio)
            val command = commandText(heard)
            if (command.isNotEmpty()) {
                emit(PipelineEvent.Command(command, heard, audio.size / SAMPLE_RATE.toFloat(), millis))
                backToWakeWord()
                return
            }
            // Only the wake word's tail, the beep or a filler: keep waiting for the actual command.
            emit(PipelineEvent.Ignored(heard))
            heardSpeech = false
            listenStartedAt = clock
        }
        val waitedTooLong = !heardSpeech && clock - listenStartedAt > WAIT_FOR_COMMAND_SECONDS * SAMPLE_RATE
        if (waitedTooLong || clock - listenFirstStartedAt > MAX_LISTEN_SECONDS * SAMPLE_RATE) {
            emit(PipelineEvent.Timeout)
            backToWakeWord()
        }
    }

    /** Silero triggers a little after speech onset, which clips plosives ("Call" -> "all"); pad backwards. */
    private fun popSegment(): FloatArray {
        val segment = vad.front()
        vad.pop()
        val start = (segment.start - commandAudio.dropped).toInt()
        val end = start + segment.samples.size
        if (start < 0 || end > commandAudio.size) return segment.samples
        return commandAudio.range(maxOf(0, start - PAD_SAMPLES), end)
    }

    private fun transcribe(audio: FloatArray): Pair<String, Long> {
        emit(PipelineEvent.Transcribing)
        val started = SystemClock.elapsedRealtime()
        val s = asr.createStream()
        s.acceptWaveform(audio, SAMPLE_RATE)
        asr.decode(s)
        val text = asr.getResult(s).text.trim()
        s.release()
        return text to SystemClock.elapsedRealtime() - started
    }

    private fun backToWakeWord() {
        listening = false
        wakeQuietUntil = clock + SAMPLE_RATE // the command's own audio must not re-trigger the wake model
        stream.release()
        stream = kws.createStream()
        kwsFed = 0
        history.clear()
    }

    fun release() {
        wakeModel?.close()
        stream.release()
        kws.release()
        vad.release()
        asr.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val KWS_DIR = "models/kws"
        private const val VAD_WINDOW = 512 // silero window at 16 kHz
        // The spotter can report seconds late during continuous speech, so keep a long tail.
        private const val HISTORY_SECONDS = 10
        private const val WAIT_FOR_COMMAND_SECONDS = 6
        private const val MAX_LISTEN_SECONDS = 15
        // People pause mid-command ("set an alarm... for six"); 0.6 s cut them off.
        private const val END_SILENCE_SECONDS = 0.8f
        private const val MAX_COMMAND_SECONDS = 10f
        private const val PAD_SAMPLES = SAMPLE_RATE / 5
        private const val PREROLL_SAMPLES = SAMPLE_RATE / 2
    }
}

/** Fixed-capacity buffer that keeps the most recent samples. */
private class RollingAudio(private val capacity: Int) {
    private val data = FloatArray(capacity)
    var size = 0
        private set

    /** Samples discarded from the front since the last [clear]; maps absolute indices into [data]. */
    var dropped = 0L
        private set

    fun clear() {
        size = 0
        dropped = 0
    }

    fun append(samples: FloatArray) {
        val overflow = size + samples.size - capacity
        if (overflow > 0) {
            System.arraycopy(data, overflow, data, 0, size - overflow)
            size -= overflow
            dropped += overflow
        }
        System.arraycopy(samples, 0, data, size, samples.size)
        size += samples.size
    }

    fun tail(n: Int): FloatArray = data.copyOfRange(size - n.coerceIn(0, size), size)

    fun range(from: Int, to: Int): FloatArray = data.copyOfRange(from.coerceIn(0, size), to.coerceIn(0, size))
}
