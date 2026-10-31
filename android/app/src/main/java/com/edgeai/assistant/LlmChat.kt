package com.edgeai.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FunctionCall(val name: String, val args: List<Pair<String, String>>)

data class ChatTurn(
    val id: Long,
    val user: String,
    val fromVoice: Boolean,
    val pending: Boolean = true,
    val call: FunctionCall? = null,
    val raw: String = "",
    val error: String? = null,
    val prefillTokens: Int = 0,
    val prefillMillis: Double = 0.0,
    val decodeTokens: Int = 0,
    val decodeMillis: Double = 0.0,
    val totalMillis: Long = 0,
)

/** Sends commands to [LlmServer] with the schema from finetune/functions.json and parses the reply. */
object LlmChat {
    private const val SYSTEM = "You are a model that can do function calling with the following functions"

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    /** Also run transcribed voice commands through the model (set from the Chat tab). */
    val routeVoice = MutableStateFlow(true)

    private var tools: JSONArray? = null

    fun clear() = _turns.update { emptyList() }

    fun warmUp(context: Context) {
        request(context, "Turn on the flashlight")
    }

    /** Blocking; call off the main thread. */
    fun send(context: Context, text: String, fromVoice: Boolean = false) {
        val id = SystemClock.elapsedRealtimeNanos()
        _turns.update { it + ChatTurn(id, text, fromVoice) }
        val started = SystemClock.elapsedRealtime()
        val result = try {
            val out = request(context, text)
            val content = out.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
            val timings = out.optJSONObject("timings") ?: JSONObject()
            ChatTurn(
                id, text, fromVoice, pending = false,
                call = parseCall(content),
                raw = content,
                prefillTokens = timings.optInt("prompt_n"),
                prefillMillis = timings.optDouble("prompt_ms"),
                decodeTokens = timings.optInt("predicted_n"),
                decodeMillis = timings.optDouble("predicted_ms"),
                totalMillis = SystemClock.elapsedRealtime() - started,
            )
        } catch (e: Exception) {
            ChatTurn(id, text, fromVoice, pending = false, error = e.message ?: e.toString())
        }
        _turns.update { list -> list.map { if (it.id == id) result else it } }
        log(context, result)
    }

    /** One JSON line per turn in files/llm/chat_log.jsonl (and logcat), for finetune/ analysis. */
    private fun log(context: Context, t: ChatTurn) {
        val line = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("text", t.user)
            .put("source", if (t.fromVoice) "voice" else "typed")
            .put("raw", t.raw)
            .put("call", t.call?.let { c -> JSONObject().put("name", c.name).put("args", JSONObject(c.args.toMap())) } ?: JSONObject.NULL)
            .put("error", t.error ?: JSONObject.NULL)
            .put("prefill_ms", t.prefillMillis).put("decode_ms", t.decodeMillis).put("total_ms", t.totalMillis)
            .toString()
        Log.i("EdgeChat", line)
        runCatching { File(LlmServer.modelDir(context), "chat_log.jsonl").appendText(line + "\n") }
    }

    private fun request(context: Context, text: String): JSONObject {
        val tools = tools ?: JSONArray(context.assets.open("functions.json").bufferedReader().readText()).also { tools = it }
        val body = JSONObject()
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "developer").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", text)))
            .put("tools", tools)
            .put("temperature", 0)
            .put("max_tokens", 96)
            .put("cache_prompt", true)
            // FunctionGemma keeps generating ("<start_function_response>...") after the call.
            .put("stop", JSONArray().put("<end_function_call>"))
        val conn = URL("http://127.0.0.1:${LlmServer.PORT}/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text2 = stream.bufferedReader().readText()
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}: $text2" }
        return JSONObject(text2)
    }
}

private val CALL = Regex("""call:([A-Za-z_][A-Za-z0-9_]*)\{(.*)\}?""", RegexOption.DOT_MATCHES_ALL)
private const val ESC = "<escape>"

/** Parses FunctionGemma output like `<start_function_call>call:set_alarm{hour:6,day:<escape>today<escape>}`. */
internal fun parseCall(content: String): FunctionCall? {
    val m = CALL.find(content) ?: return null
    val body = m.groupValues[2].removeSuffix("}")
    val args = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < body.length) {
        val colon = body.indexOf(':', i)
        if (colon < 0) break
        val key = body.substring(i, colon).trim().trimStart(',', '{').trim()
        var j = colon + 1
        val value: String
        if (body.startsWith(ESC, j)) {
            val end = body.indexOf(ESC, j + ESC.length).let { if (it < 0) body.length else it }
            value = body.substring(j + ESC.length, end)
            j = end + ESC.length
        } else {
            val end = body.indexOf(',', j).let { if (it < 0) body.length else it }
            value = body.substring(j, end).trim()
            j = end
        }
        if (key.isNotEmpty()) args += key to value
        i = if (j < body.length && body[j] == ',') j + 1 else j
    }
    return FunctionCall(m.groupValues[1], args)
}
