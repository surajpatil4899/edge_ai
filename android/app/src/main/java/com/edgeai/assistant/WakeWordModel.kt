package com.edgeai.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * Personal wake-word detector trained by prototype/train_wakeword.py: openWakeWord's melspectrogram
 * and embedding models produce one 96-d embedding per 80 ms; a small MLP scores the last 16 (1.28 s).
 * Streaming mirrors openWakeWord: each 1280-sample step runs the mel model on that step plus 480
 * samples of context (8 new frames), then one embedding over the latest 76 frames.
 */
class WakeWordModel(assets: AssetManager) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val mel = env.createSession(assets.open("models/oww/melspectrogram.onnx").readBytes(), options)
    private val emb = env.createSession(assets.open("models/oww/embedding_model.onnx").readBytes(), options)
    private val head = Head(JSONObject(assets.open("wakeword.json").bufferedReader().readText()))
    private val patience = head.patience
    private val gate = Math.pow(10.0, head.gateDbfs / 20.0).toFloat()
    private var aboveThreshold = 0
    private var runPeak = 0f

    /** Highest score during the steps that made the last detection, for the logs. */
    var lastDetectionScore = 0f
        private set
    private val stepPeaks = ArrayDeque<Float>()

    private val audio = FloatArray(STEP + CONTEXT) // last step plus context, int16-scaled
    private var pending = FloatArray(0)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()

    /**
     * Feeds samples in [-1, 1]; true when the wake phrase is detected: score above threshold for
     * [patience] consecutive 80 ms steps while the last ~1.3 s is louder than the gate (rule shared with
     * prototype/train_wakeword.py, where it was tuned on real phone recordings).
     */
    fun accept(samples: FloatArray): Boolean {
        pending = pending + samples
        var detected = false
        while (pending.size >= STEP) {
            var peak = 0f
            for (i in 0 until STEP) peak = maxOf(peak, kotlin.math.abs(pending[i]))
            System.arraycopy(audio, STEP, audio, 0, CONTEXT)
            for (i in 0 until STEP) audio[CONTEXT + i] = pending[i] * 32767f
            pending = pending.copyOfRange(STEP, pending.size)
            stepPeaks.addLast(peak)
            while (stepPeaks.size > head.window) stepPeaks.removeFirst()
            val loud = stepPeaks.maxOrNull()!! > gate
            val score = step()
            if (score > head.threshold && loud) {
                runPeak = if (aboveThreshold == 0) score else maxOf(runPeak, score)
                aboveThreshold++
            } else {
                aboveThreshold = 0
            }
            if (aboveThreshold >= patience) {
                aboveThreshold = 0
                lastDetectionScore = runPeak
                detected = true
            }
        }
        return detected
    }

    fun reset() {
        audio.fill(0f)
        pending = FloatArray(0)
        melFrames.clear()
        embeddings.clear()
        stepPeaks.clear()
        aboveThreshold = 0
    }

    private fun step(): Float {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
            mel.run(mapOf("input" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val frames = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0] // [1][1][8][32]
                for (f in frames) melFrames.addLast(FloatArray(32) { f[it] / 10f + 2f })
            }
        }
        while (melFrames.size > MEL_WINDOW) melFrames.removeFirst()
        if (melFrames.size < MEL_WINDOW) return -1f

        val flat = FloatArray(MEL_WINDOW * 32)
        melFrames.forEachIndexed { i, f -> System.arraycopy(f, 0, flat, i * 32, 32) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, MEL_WINDOW.toLong(), 32, 1)).use { input ->
            emb.run(mapOf("input_1" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val e = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0][0] // [1][1][1][96]
                embeddings.addLast(e.copyOf())
            }
        }
        while (embeddings.size > head.window) embeddings.removeFirst()
        if (embeddings.size < head.window) return -1f
        return head.score(embeddings)
    }

    override fun close() {
        mel.close()
        emb.close()
        options.close()
    }

    private class Head(json: JSONObject) {
        val window = json.getInt("window")
        val threshold = json.getDouble("threshold").toFloat()
        val patience = json.optInt("patience", 1)
        val gateDbfs = json.optDouble("gate_dbfs", -100.0)
        private val mean = json.getJSONArray("mean").toFloats()
        private val std = json.getJSONArray("std").toFloats()
        private val w1 = json.getJSONArray("w1").let { rows -> Array(rows.length()) { rows.getJSONArray(it).toFloats() } }
        private val b1 = json.getJSONArray("b1").toFloats()
        private val w2 = json.getJSONArray("w2").toFloats()
        private val b2 = json.getDouble("b2").toFloat()

        fun score(embeddings: Collection<FloatArray>): Float {
            val x = FloatArray(window * 96)
            embeddings.forEachIndexed { i, e -> System.arraycopy(e, 0, x, i * 96, 96) }
            val hidden = b1.copyOf()
            for (i in x.indices) {
                val v = (x[i] - mean[i]) / std[i]
                if (v == 0f) continue
                val row = w1[i]
                for (j in hidden.indices) hidden[j] += v * row[j]
            }
            var z = b2
            for (j in hidden.indices) if (hidden[j] > 0) z += hidden[j] * w2[j]
            return (1 / (1 + exp(-z.toDouble()))).toFloat()
        }

        private fun JSONArray.toFloats() = FloatArray(length()) { getDouble(it).toFloat() }
    }

    companion object {
        private const val STEP = 1280 // 80 ms
        private const val CONTEXT = 480 // 3 mel hops of left context, as in openWakeWord
        private const val MEL_WINDOW = 76
    }
}
