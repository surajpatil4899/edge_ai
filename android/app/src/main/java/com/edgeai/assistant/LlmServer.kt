package com.edgeai.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Runs llama.cpp's llama-server (bundled as libllama_server_exec.so, arm64 only) as a child process on
 * 127.0.0.1:[PORT]. Android only lets apps execute files from their native library dir, hence the
 * library-style name. The model ships in the APK (see [findModel]).
 * Stopgap; the app will later call llama.cpp through JNI instead.
 */
object LlmServer {
    const val PORT = 8080
    private const val BUNDLED_DIR = "models/llm"
    private const val TAG = "EdgeLlm"

    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Ready(val model: String, val loadMillis: Long) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var process: Process? = null

    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), "llm").apply { mkdirs() }

    /**
     * The model the app ships with (a .gguf under assets/models/llm, extracted once to internal storage because
     * llama.cpp needs a file path). Only builds without a bundled model fall back to a .gguf side-loaded into
     * the external llm/ folder, so a stale side-loaded model can never shadow the shipped one.
     */
    fun findModel(context: Context): File? =
        bundledModel(context) ?: modelDir(context).listFiles { f -> f.extension == "gguf" }?.maxByOrNull { it.lastModified() }

    private fun bundledModel(context: Context): File? {
        val name = context.assets.list(BUNDLED_DIR)?.firstOrNull { it.endsWith(".gguf") } ?: return null
        val asset = "$BUNDLED_DIR/$name"
        val size = context.assets.openFd(asset).use { it.length } // stored uncompressed (noCompress "gguf")
        val dir = File(context.filesDir, "llm").apply { mkdirs() }
        val out = File(dir, name)
        if (out.length() != size) {
            dir.listFiles()?.forEach { it.delete() } // previous bundled versions
            val tmp = File(dir, "$name.part")
            context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(tmp.renameTo(out)) { "could not extract $name" }
        }
        return out
    }

    @Synchronized // the listener, a voice command and the Chat tab may all ask at once
    fun start(context: Context) {
        if (_state.value is State.Starting || _state.value is State.Ready) return
        val app = context.applicationContext
        _state.value = State.Starting
        thread(name = "llm-server") {
            val started = SystemClock.elapsedRealtime()
            try {
                val model = findModel(app) ?: error("No command model bundled in this build")
                AppLogs.activity("command model file ready (${model.name}) after ${SystemClock.elapsedRealtime() - started} ms")
                val libDir = app.applicationInfo.nativeLibraryDir
                val exe = File(libDir, "libllama_server_exec.so")
                check(exe.exists()) { "llama-server is only bundled for arm64 phones" }
                if (!healthy()) {
                    val pb = ProcessBuilder(
                        // One slot: with several, a command could land on a slot without the cached tool
                        // declarations and pay a full prefill (280-350 ms vs 70-100 ms on the vivo in the field logs).
                        exe.path, "-m", model.path, "--jinja", "-t", "4", "-c", "4096", "--parallel", "1",
                        "--host", "127.0.0.1", "--port", PORT.toString(),
                    ).redirectErrorStream(true)
                    pb.environment()["LD_LIBRARY_PATH"] = libDir
                    val p = pb.start()
                    process = p
                    serverOutput.clear()
                    thread(name = "llm-log", isDaemon = true) {
                        // The stream closes when the process is stopped; that must not crash the app.
                        runCatching {
                            p.inputStream.bufferedReader().forEachLine { line ->
                                Log.d(TAG, line)
                                synchronized(serverOutput) {
                                    serverOutput.addLast(line)
                                    while (serverOutput.size > 40) serverOutput.removeFirst()
                                }
                            }
                        }
                    }
                    val launched = SystemClock.elapsedRealtime()
                    while (!healthy()) {
                        check(p.isAlive) { "llama-server exited (code ${p.exitValue()})" }
                        check(SystemClock.elapsedRealtime() - launched < 90_000) {
                            "llama-server did not become ready in 90 s (last check: $lastHealthError)"
                        }
                        Thread.sleep(200)
                    }
                }
                // Warm-up: the developer turn with all tool declarations (~1000 tokens) is the slow part;
                // once cached, each command only costs its own tokens.
                LlmChat.warmUp(app)
                _state.value = State.Ready(model.name, SystemClock.elapsedRealtime() - started)
                AppLogs.activity("command model ready: ${model.name} in ${SystemClock.elapsedRealtime() - started} ms")
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                AppLogs.activity("command model failed: ${e.message}")
                synchronized(serverOutput) {
                    if (serverOutput.isNotEmpty()) AppLogs.activity("llama-server output (last ${serverOutput.size} lines):\n" + serverOutput.joinToString("\n"))
                }
                stop()
                _state.value = State.Failed(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        process?.destroy()
        process = null
        _state.value = State.Stopped
    }

    @Volatile private var lastHealthError = "none yet"
    private val serverOutput = ArrayDeque<String>()

    private fun healthy(): Boolean = try {
        (URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection).run {
            connectTimeout = 300
            readTimeout = 300
            val code = responseCode
            lastHealthError = "HTTP $code" // 503 while the model is still loading
            code == 200
        }
    } catch (e: Exception) {
        lastHealthError = "${e.javaClass.simpleName}: ${e.message}"
        false
    }
}
