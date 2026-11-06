package com.edgeai.assistant

import android.content.Context
import android.os.SystemClock
import com.edgeai.assistant.CommandGuard.Route

/**
 * One command end to end: guard -> model -> function calls -> guard -> actions on the phone -> spoken reply.
 * A sentence with several actions ("turn on DND and set an alarm for 6") runs them in order with one combined
 * reply. Blocking.
 */
object CommandRunner {
    fun run(context: Context, text: String, fromVoice: Boolean, execute: Boolean) {
        val app = context.applicationContext
        val pre = CommandGuard.beforeModel(text)
        val turn: ChatTurn
        val routes: List<Route>
        if (pre != null) {
            routes = listOf(pre)
            turn = LlmChat.record(text, fromVoice, pre.why)
        } else {
            if (!awaitModel(app)) {
                AppLogs.activity("model not ready for: \"$text\" (${LlmServer.state.value})")
                if (execute) Speaker.speak(app, "I'm still getting ready. Please try again in a moment.")
                return
            }
            val t = LlmChat.send(app, text, fromVoice)
            routes = when {
                t.error != null -> listOf(Route.Answer(ActionResult(false, "Sorry, something went wrong."), "error"))
                t.calls.isEmpty() -> listOf(Route.Answer(ActionResult(false, "Sorry, I can't help with that."), "no call"))
                else -> t.calls.map { CommandGuard.afterModel(text, it, LlmChat.enums(app), single = t.calls.size == 1) }
            }
            val why = routes.map { it.why }.filter { it != "model" && it != "no call" && it != "error" }.joinToString("; ").ifEmpty { null }
            turn = t.copy(guard = why)
            if (why != null) LlmChat.setGuard(t.id, why)
        }
        if (turn.guard != null) AppLogs.activity("guard: ${turn.guard}")
        if (!execute) {
            LlmChat.log(app, turn)
            return
        }
        val started = SystemClock.elapsedRealtime()
        val executor = ActionExecutor(app)
        val results = routes.map { route ->
            when (route) {
                is Route.Answer -> route.result
                is Route.Call -> executor.execute(route.call).let { r -> if (r.ok && !r.deferred && route.note != null) r.copy(say = route.note) else r }
            }
        }
        // "Do not disturb is on. Alarm set for 6:00 AM tomorrow."
        val result = ActionResult(results.all { it.ok }, results.map { it.say }.distinct().joinToString(" "), results.any { it.deferred })
        val done = turn.copy(action = result, actionMillis = SystemClock.elapsedRealtime() - started)
        LlmChat.setAction(turn.id, result)
        LlmChat.log(app, done)
        AssistantState.info((if (result.ok) "✓ " else "✗ ") + result.say + " (model ${turn.totalMillis} ms, action ${done.actionMillis} ms)")
        Speaker.speak(app, result.say)
    }

    /** The model loads when listening starts; a command right after that waits for it. */
    private fun awaitModel(context: Context): Boolean {
        LlmServer.start(context)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (true) {
            when (LlmServer.state.value) {
                is LlmServer.State.Ready -> return true
                is LlmServer.State.Failed -> return false
                else -> if (SystemClock.elapsedRealtime() > deadline) return false
            }
            Thread.sleep(100)
        }
    }
}
