package com.edgeai.assistant

import org.json.JSONArray

/**
 * Rules around the command model, each from a failure in the field logs: what never reaches the model
 * (background speech, garbled one-word transcripts, help questions) and what it may not do with a
 * transcript (set an alarm on "stop the alarm", a bare "play" for "play X on Spotify", invent an app name). Pure.
 */
object CommandGuard {
    sealed interface Route {
        val why: String

        /** Reply without acting. */
        data class Answer(val result: ActionResult, override val why: String) : Route

        /** Carry out this call instead of (or before asking) the model. */
        data class Call(val call: FunctionCall, override val why: String, val note: String? = null) : Route
    }

    const val CAPABILITIES = "I can set alarms, timers and reminders, call or message your contacts, also on WhatsApp, " +
        "play music and videos, give directions, search the web, control the flashlight, volume and do not disturb, " +
        "and open apps or the camera. You can ask for two things at once. For example: play Arijit Singh on YouTube."
    private val NOT_CAUGHT = ActionResult(false, "Sorry, I didn't catch that.")

    private val TOKEN = Regex("""\S+""")
    private val LETTERS = Regex("""[a-z]""")
    /** Commands that are complete in one word; any other single word is usually a misheard fragment ("Conde"). */
    private val ONE_WORD = setOf(
        "stop", "pause", "play", "resume", "next", "skip", "previous", "back", "mute", "unmute", "snooze", "dismiss",
        "flashlight", "torch", "louder", "quieter", "silent", "vibrate", "help",
    )
    private const val MAX_WORDS = 30

    private val HELP = Regex(
        """\bwhat (all |else )?(can|could|do) you do\b|\byou can do\b|\bwhat else can you\b|\bwhat are (the |all )?(tasks|things|commands|features)\b|""" +
            """\bwhat (all )?can i (say|ask)\b|\bhow (do|can) i use you\b|^help( me)?$""",
    )
    private val TIMER_CONTROL = Regex("""\b(pause|resume|stop|cancel|restart|reset|delete)\b.*\btimers?\b|\btimers?\b.*\b(pause|resume|stop|cancel)\b""")
    // "stop the alarm", "cancel my 7 am alarm", "it's top the alarm" (ASR); not "stop the music and set an alarm".
    private val STOP_ALARM = Regex("""(\b(stop|cancel|delete|remove|dismiss|disable)|\b(turn|switch|shut)(ed)? off|'s top)\s+(\S+\s+){0,3}?alarms?\b""")
    private val SELF = setOf("me", "myself", "my self", "self", "i")
    private val PLAY_FILLER = setOf(
        "music", "song", "songs", "it", "again", "the", "some", "any", "a", "my", "media", "please", "now", "something", "this", "that",
    )
    // "play little things on spotify" -> query, app; a following clause ("and call dad") is not part of the query.
    private val PLAY_QUERY = Regex(
        """\bplay\s+(.+?)(?:\s+(?:on|in|from)\s+(spotify|youtube music|yt music|youtube|you tube|gaana|jiosaavn|saavn|wynk|amazon music))?""" +
            """(?=\s+(?:and|then|also)\b|[,.!?]|$)""",
    )
    /** Functions that open another app at a specific spot (schema v3); they already are "open X and do Y". */
    private val DEEP_LINKS = setOf("play_media", "web_search", "navigate", "search_maps", "message_app", "camera", "search_app_store")
    // Only at the start: "remind me to open the door and lock it" is a reminder, not a composite command.
    private val OPEN_AND = Regex("""^(?:please\s+)?open\s+(.+?)\s+and\s+(.+)""")

    /** Applied to the cleaned transcript before the model; null = ask the model. */
    fun beforeModel(text: String): Route? {
        val t = norm(text)
        val tokens = TOKEN.findAll(t).map { it.value.trim('.', ',', '!', '?', '"') }.filter { it.isNotEmpty() }.toList()
        if (tokens.none { LETTERS.containsMatchIn(it) }) return Route.Answer(NOT_CAUGHT, "no words")
        if (tokens.size > MAX_WORDS || repeats(tokens)) return Route.Answer(NOT_CAUGHT, "background speech")
        if (tokens.size == 1 && tokens[0] !in ONE_WORD) return Route.Answer(NOT_CAUGHT, "single unknown word")
        if (HELP.containsMatchIn(t)) return Route.Answer(ActionResult(true, CAPABILITIES), "help")
        if (TIMER_CONTROL.containsMatchIn(t)) {
            return Route.Call(FunctionCall("show_timers", emptyList()), "timer control", "Here are your timers.")
        }
        return null
    }

    /**
     * Checks one of the model's calls against the transcript and the schema ([enums]: function -> argument -> allowed
     * values). Returns the call to carry out (maybe corrected) or an answer instead. [single]: the only call for this
     * transcript; with several ("turn on DND and set an alarm"), whole-sentence rewrites are skipped.
     */
    fun afterModel(text: String, call: FunctionCall, enums: Map<String, Map<String, Set<String>>>, single: Boolean = true): Route {
        val t = norm(text)
        val args = call.args.toMap()

        // "Stop the alarm of six am" came back as set_alarm(six am): never set an alarm the user asked to stop.
        if (call.name == "set_alarm" && STOP_ALARM.containsMatchIn(t) && "snooze" !in t) {
            val time = args["time"]?.takeIf { SpokenTime.parseTime(it) != null }
            return Route.Call(FunctionCall("dismiss_alarm", listOfNotNull("action" to "dismiss", time?.let { "time" to it })), "stop, not set")
        }

        enums[call.name]?.forEach { (arg, allowed) ->
            val v = args[arg] ?: return@forEach
            if (v.lowercase() !in allowed) return Route.Answer(NOT_CAUGHT, "$arg=$v not in schema")
        }

        when (call.name) {
            "call_contact", "send_message", "message_app" -> {
                val who = args["contact"]?.lowercase()?.trim()
                if (who in SELF) return Route.Answer(ActionResult(false, "I don't know your own number. Say the name of a contact."), "self")
            }
            "open_app" -> {
                // "Open what's up" came back as open_app(music media): use the words actually said.
                val app = args["app"].orEmpty()
                if (!grounded(app, t)) {
                    val said = Regex("""\bopen\s+(?:the\s+)?(.+?)(?:\s+app)?$""").find(t)?.groupValues?.get(1)?.let(::stripPunct)
                    return if (said.isNullOrBlank()) Route.Answer(NOT_CAUGHT, "app '$app' not said")
                    else Route.Call(FunctionCall("open_app", listOf("app" to said)), "app '$app' not said, using '$said'")
                }
            }
            "media_control" -> if (args["action"] == "play") {
                // "Play little things on Spotify" came back as a bare play (v2 model): search for what was asked.
                PLAY_QUERY.find(t)?.let { m ->
                    val query = stripPunct(m.groupValues[1])
                    if (TOKEN.findAll(query).any { stripPunct(it.value) !in PLAY_FILLER }) {
                        val app = m.groupValues[2].ifEmpty { OPEN_AND.find(t)?.groupValues?.get(1)?.takeIf { DeepLinks.mediaApp(it) != null }.orEmpty() }
                        return Route.Call(FunctionCall("play_media", listOfNotNull("query" to query, app.takeIf { it.isNotEmpty() }?.let { "app" to it })), "play query")
                    }
                }
            }
        }

        // "Open YouTube and play a Bollywood song": do the first step and say what is missing.
        OPEN_AND.find(t)?.let { m ->
            if (single && call.name != "open_app" && call.name !in DEEP_LINKS) {
                val app = stripPunct(m.groupValues[1])
                return Route.Call(FunctionCall("open_app", listOf("app" to app)), "composite",
                    "Opening ${title(app)} for you.")
            }
        }

        // Free text the model copied must be in the transcript; a reminder about words nobody said is worse than none.
        for (arg in listOf("contact", "text", "message", "query", "destination")) {
            val v = args[arg] ?: continue
            if (!grounded(v, t)) return Route.Answer(NOT_CAUGHT, "$arg '$v' not said")
        }
        return Route.Call(call, "model")
    }

    // A factual question the model has no function for ("what is the weather today"): searching beats "can't help".
    // Not chit-chat about the assistant or the user ("how are you", "what are you doing").
    private val QUESTION = Regex("""^(what|what's|whats|who|who's|when|where|which|how|why|is|are|will|does|did|can)\b""")
    // Requests for information that mention "me" but aren't about the user ("tell me about the Eiffel tower").
    private val LOOKUP = Regex("""^(tell me about|tell me the|explain|define|meaning of|how to|search for|look up|show me the)\b""")
    // Lookups even when ASR drops the question word ("Happened to U. S. economy" for "What happened to...").
    private val TOPIC = Regex("""\b(happened|happening|news|weather|forecast|score|scores|price|prices|rate|rates|latest|results?|update|updates|stock|election|elections|economy|capital of|population|distance)\b""")
    private val PERSONAL = Regex("""\b(you|your|yours|i|i'm|me|my|mine)\b""")
    // ASR's rendering of the wake word's tail before the question ("Which what is the weather today").
    private val RESIDUE = Regex("""^(which|wich|it's|its|is|okay|ok|and|a|he|edge)[,.]?\s+(?=(what|who|when|where|which|how|why|tell|explain|define)\b)""")

    /** When the model made no call: search the web for a real question, else the usual "can't help". */
    fun noCall(text: String): Route {
        val t = RESIDUE.replace(norm(text).trimEnd('.', '?', '!'), "")
        val words = TOKEN.findAll(t).count()
        // "?" or "!" mid-text means several sentences, usually background speech; "." can be an abbreviation ("u.s.").
        val oneSentence = !Regex("""[?!]\s""").containsMatchIn(t)
        val asks = LOOKUP.containsMatchIn(t) || ((QUESTION.containsMatchIn(t) || TOPIC.containsMatchIn(t)) && !PERSONAL.containsMatchIn(t))
        if (asks && words >= 3 && oneSentence) {
            return Route.Call(FunctionCall("web_search", listOf("query" to t)), "question -> web search",
                "Let me look that up on Google for you.")
        }
        return Route.Answer(ActionResult(false, "I'm not sure how to help with that one. Try asking \"what can you do?\""), "no call")
    }

    /** Allowed values per function and argument, from functions.json. */
    fun enumsFrom(tools: JSONArray): Map<String, Map<String, Set<String>>> = buildMap {
        for (i in 0 until tools.length()) {
            val fn = tools.getJSONObject(i).getJSONObject("function")
            val props = fn.optJSONObject("parameters")?.optJSONObject("properties") ?: continue
            val perArg = buildMap {
                for (arg in props.keys()) {
                    val e = props.getJSONObject(arg).optJSONArray("enum") ?: continue
                    put(arg, (0 until e.length()).map { e.getString(it).lowercase() }.toSet())
                }
            }
            if (perArg.isNotEmpty()) put(fn.getString("name"), perArg)
        }
    }

    /** True when most words of [value] appear (allowing ASR-style spelling differences) in the transcript. */
    internal fun grounded(value: String, transcript: String): Boolean {
        val said = words(transcript)
        val want = words(value).filter { it.length >= 3 }.ifEmpty { words(value) }
        if (want.isEmpty()) return true
        val joined = said.joinToString("")
        val hits = want.count { w -> w in joined || said.any { ContactMatcher.similar(w, it) >= 0.7 } }
        return hits * 2 >= want.size
    }

    /** The same phrase three times ("if you don't have a car, you can't do it ...") is ASR looping on background speech. */
    private fun repeats(tokens: List<String>): Boolean {
        if (tokens.size < 9) return false
        val grams = tokens.windowed(3).groupingBy { it }.eachCount()
        return grams.values.any { it >= 3 }
    }

    private fun norm(s: String) = s.lowercase().replace('’', '\'').trim()
    private fun words(s: String) = ContactMatcher.normalize(s.replace("'", "")).split(" ").filter { it.isNotEmpty() }
    private fun stripPunct(s: String) = s.trim().trim('.', ',', '!', '?', '"').trim()
    private fun title(s: String) = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
