package com.edgeai.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Turns the model's spoken time/day fields into a concrete date-time. Pure, for unit tests. */
object TimeResolver {
    private val dayPeriod = Regex("""\b(am|pm|a\.?\s*m|p\.?\s*m|morning|afternoon|evening|night|tonight|noon|midnight|midday)\b""", RegexOption.IGNORE_CASE)

    /** True when "6" could be 6:00 or 18:00, i.e. no am/pm or part-of-day cue and a 1-12 hour. */
    fun isAmbiguous(spoken: String, hour: Int): Boolean = !dayPeriod.containsMatchIn(spoken) && hour in 1..12

    /** The date a `day` value refers to, or null for no day ("today" and weekdays resolve from [now]). */
    fun date(day: String?, now: LocalDateTime): LocalDate? = when (day?.lowercase()) {
        null, "" -> null
        "today" -> now.toLocalDate()
        "tomorrow" -> now.toLocalDate().plusDays(1)
        else -> runCatching { DayOfWeek.valueOf(day.uppercase()) }.getOrNull()
            ?.let { now.toLocalDate().with(TemporalAdjusters.nextOrSame(it)) }
    }

    /**
     * Next moment matching [spokenTime] on [day]. An ambiguous "6" means whichever of 6:00/18:00 comes next.
     * Without a day, a time already past today rolls to tomorrow; with a named weekday that is today
     * but already past, next week.
     */
    fun resolve(spokenTime: String, day: String?, now: LocalDateTime): LocalDateTime? {
        relative(spokenTime, now)?.let { return it }
        val (h, m) = SpokenTime.parseTime(spokenTime) ?: return null
        val candidates = if (isAmbiguous(spokenTime, h)) listOf(h % 12, h % 12 + 12) else listOf(h)
        val explicit = date(day, now)
        val startDate = explicit ?: now.toLocalDate()
        for (offset in 0..7L) {
            val d = startDate.plusDays(offset)
            val next = candidates.map { LocalDateTime.of(d, LocalTime.of(it, m)) }.filter { it.isAfter(now) }.minOrNull()
            if (next != null) {
                // A named weekday must stay on that weekday: if today's slot has passed, go to next week's.
                if (explicit != null && day?.lowercase() !in listOf("today", "tomorrow") && d != explicit) {
                    return LocalDateTime.of(explicit.plusDays(7), LocalTime.of(candidates.first(), m))
                }
                return next
            }
        }
        return null
    }

    // "6 hours", "in 20 minutes", "after an hour and a half": a delay, not a clock time ("set alarm after 6 hours"
    // used to ring at 6:00 AM). "10 minutes past 6" names a clock time and is left to parseTime.
    private val relativeTime = Regex(
        """^(?:in|after)?\s*(.+?(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|and a half))\s*(?:from now|later)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val clockWords = Regex("""\b(past|to|before|after)\b""", RegexOption.IGNORE_CASE)

    /** [now] plus the spoken delay, to the minute, or null when [spokenTime] is not a delay. */
    fun relative(spokenTime: String, now: LocalDateTime): LocalDateTime? {
        val delay = relativeTime.find(spokenTime.trim())?.groupValues?.get(1)?.takeIf { !clockWords.containsMatchIn(it) } ?: return null
        val seconds = SpokenTime.parseDuration(delay)?.takeIf { it > 0 } ?: return null
        val t = now.plusSeconds(seconds.toLong()).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        return if (t.isAfter(now)) t else t.plusMinutes(1)
    }

    /** "6:00 AM tomorrow", "6:30 PM", "9:00 AM Monday": how the spoken reply states the time. */
    fun describe(t: LocalDateTime, now: LocalDateTime): String {
        val h12 = (t.hour + 11) % 12 + 1
        val clock = "%d:%02d %s".format(h12, t.minute, if (t.hour < 12) "AM" else "PM")
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        return when {
            days == 0L -> clock
            days == 1L -> "$clock tomorrow"
            days < 7 -> "$clock on ${t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
            else -> "$clock on ${t.toLocalDate()}"
        }
    }
}
