package com.edgeai.assistant

/**
 * Parses times and durations as spoken / as Moonshine writes them ("6 a.m", "seven thirty",
 * "half past 6", "an hour and a half"). The model copies these phrases verbatim (schema v2) and
 * this turns them into numbers. Twin of finetune/spoken.py; both are tested against
 * finetune/spoken_cases.json.
 */
object SpokenTime {
    private val units = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val pmWords = setOf("pm", "afternoon", "evening", "tonight", "night")
    private val amWords = setOf("am", "morning")

    private fun clean(text: String): String {
        var t = text.lowercase().replace("-", " ")
        t = Regex("""\ba\.?\s*m\b\.?""").replace(t, " am ")
        t = Regex("""\bp\.?\s*m\b\.?""").replace(t, " pm ")
        t = Regex("""(\d)(am|pm)\b""").replace(t, "$1 $2")
        t = Regex("""(\d)\.(\d\d)\b""").replace(t, "$1:$2")
        t = t.replace("o'clock", " ").replace("oclock", " ")
        t = Regex("""[^a-z0-9:. ]""").replace(t, " ")
        return Regex("""(?<!\d)\.|\.(?!\d)""").replace(t, " ")
    }

    private fun wordsToNumbers(t: String): List<String> {
        val words = t.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)?.let { units[it] }
            when {
                w in tens -> {
                    var v = tens.getValue(w)
                    if (next != null && next in 1..9) {
                        v += next
                        i++
                    }
                    out += v.toString()
                }
                w == "oh" && next != null && next < 10 -> {
                    out += "0$next" // "seven oh five" -> 7 05
                    i++
                }
                w in units -> out += units.getValue(w).toString()
                else -> out += w
            }
            i++
        }
        return out
    }

    /** Hour 0-23 and minute, or null. Without am/pm cues the hour is returned as said (1-12). */
    fun parseTime(text: String): Pair<Int, Int>? {
        val t = clean(text)
        if (Regex("""\bnoon\b|\bmidday\b""").containsMatchIn(t)) return 12 to 0
        if (Regex("""\bmidnight\b""").containsMatchIn(t)) return 0 to 0
        val s = wordsToNumbers(t).joinToString(" ")
        var hour: Int
        var minute: Int
        val rel = Regex("""\b(half|quarter|\d{1,2})\s+(?:minutes?\s+)?(past|after|to|before)\s+(\d{1,2})\b""").find(s)
        if (rel != null) {
            val (amountWord, direction, h) = rel.destructured
            val amount = when (amountWord) { "half" -> 30; "quarter" -> 15; else -> amountWord.toInt() }
            if (direction == "past" || direction == "after") {
                hour = h.toInt()
                minute = amount
            } else {
                hour = ((h.toInt() - 1) % 24).let { if (it == 0) (if (h.toInt() <= 12) 12 else 0) else it }
                minute = 60 - amount
            }
        } else {
            val m = Regex("""\b(\d{1,2}):(\d{2})\b""").find(s)
                ?: Regex("""\b(\d{1,2})\s+(\d{1,2})\b""").find(s)
                ?: Regex("""\b(\d{1,2})(\d{2})\b""").find(s)
                ?: Regex("""\b(\d{1,2})\b()""").find(s)
                ?: return null
            hour = m.groupValues[1].toInt()
            minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        }
        if (hour > 23 || minute > 59) return null
        val words = s.split(" ").toSet()
        if (words.any { it in pmWords } && hour < 12) hour += 12
        else if (words.any { it in amWords } && hour == 12) hour = 0
        return hour to minute
    }

    /** Total seconds, or null. */
    fun parseDuration(text: String): Int? {
        var t = clean(text)
        t = Regex("""\ban? hour and a half\b""").replace(t, "90 minutes")
        t = Regex("""\bhalf an? hour\b""").replace(t, "30 minutes")
        t = Regex("""\bhalf a minute\b""").replace(t, "30 seconds")
        t = Regex("""\b(an?|one)\b(?=\s+(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b)""").replace(t, "1")
        var s = wordsToNumbers(t).joinToString(" ")
        s = Regex("""(\d+) and a half (hours?|hrs?|minutes?|mins?)""").replace(s, "$1.5 $2")
        var total = 0.0
        var found = false
        for (m in Regex("""(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""").findAll(s)) {
            found = true
            val unit = m.groupValues[2]
            total += m.groupValues[1].toDouble() * when {
                unit.startsWith("h") -> 3600
                unit.startsWith("m") -> 60
                else -> 1
            }
        }
        return if (found) Math.round(total).toInt() else null
    }
}
