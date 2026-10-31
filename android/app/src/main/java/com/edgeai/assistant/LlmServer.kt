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
 * library-style name. The model is a GGUF in getExternalFilesDir("llm"), pushed over adb:
 *   adb push model.gguf /sdcard/Android/data/com.edgeai.assistant/files/llm/
 * Stopgap for testing; the app will later call llama.cpp through JNI instead.
 */
object LlmServer {
    const val PORT = 8080
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

    fun findModel(context: Context): File? = modelDir(context).listFiles { f -> f.extension == "gguf" }?.maxByOrNull { it.lastModified() }

    fun start(context: Context) {
        if (_state.value is State.Starting || _state.value is State.Ready) return
        val app = context.applicationContext
        _state.value = State.Starting
        thread(name = "llm-server") {
            val started = SystemClock.elapsedRealtime()
            try {
                val model = findModel(app) ?: error("No .gguf model in ${modelDir(app)}; push one with adb")
                val libDir = app.applicationInfo.nativeLibraryDir
                val exe = File(libDir, "libllama_server_exec.so")
                check(exe.exists()) { "llama-server is only bundled for arm64 phones" }
                if (!healthy()) {
                    val pb = ProcessBuilder(
                        exe.path, "-m", model.path, "--jinja", "-t", "4", "-c", "4096",
                        "--host", "127.0.0.1", "--port", PORT.toString(),
                    ).redirectErrorStream(true)
                    pb.environment()["LD_LIBRARY_PATH"] = libDir
                    val p = pb.start()
                    process = p
                    thread(name = "llm-log", isDaemon = true) {
                        p.inputStream.bufferedReader().forEachLine { Log.d(TAG, it) }
                    }
                    while (!healthy()) {
                        check(p.isAlive) { "llama-server exited (code ${p.exitValue()}); see logcat tag $TAG" }
                        check(SystemClock.elapsedRealtime() - started < 60_000) { "llama-server did not start in 60 s" }
                        Thread.sleep(200)
                    }
                }
                // Warm-up: the developer turn with all tool declarations (~1000 tokens) is the slow part;
                // once cached, each command only costs its own tokens.
                LlmChat.warmUp(app)
                _state.value = State.Ready(model.name, SystemClock.elapsedRealtime() - started)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
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

    private fun healthy(): Boolean = try {
        (URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection).run {
            connectTimeout = 300
            readTimeout = 300
            responseCode == 200
        }
    } catch (_: Exception) {
        false
    }
}
