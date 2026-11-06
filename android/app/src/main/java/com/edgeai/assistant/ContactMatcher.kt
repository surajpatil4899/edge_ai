package com.edgeai.assistant

/** Picks the phone contact a spoken name refers to ("mom" -> "Mummy", "Rawl" -> "Rahul Sharma"). Pure. */
object ContactMatcher {
    /** Relation words people say vs. how such contacts are commonly saved. */
    private val relations = listOf(
        setOf("mom", "mummy", "mumma", "mommy", "mother", "maa", "ma", "amma", "aai", "mum"),
        setOf("dad", "papa", "pappa", "daddy", "father", "baba", "pa", "appa", "pitaji"),
        setOf("didi", "sister", "sis", "tai", "akka"),
        setOf("bhaiya", "bhai", "brother", "bro", "dada", "anna"),
        setOf("wife", "wifey", "biwi", "patni"),
        setOf("husband", "hubby", "pati"),
        setOf("grandma", "dadi", "nani", "aaji", "grandmother"),
        setOf("grandpa", "dada ji", "nana", "ajoba", "grandfather"),
    )

    fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\b(my|the|to)\b"""), " ").trim().replace(Regex("""\s+"""), " ")

    /** Digits if the spoken contact is a phone number ("98765 43210" -> "9876543210"), else null. */
    fun asNumber(spoken: String): String? = spoken.filter { it.isDigit() || it == '+' }.takeIf { d -> d.count { it.isDigit() } >= 3 && spoken.none { it.isLetter() } }

    /** Best matching contact name, or null if nothing is close enough. */
    fun best(spoken: String, names: Collection<String>): String? {
        val q = normalize(spoken)
        if (q.isEmpty()) return null
        val qRelation = relations.firstOrNull { q in it }
        return names.map { it to score(q, qRelation, normalize(it)) }.filter { it.second >= 0.72 }.maxByOrNull { it.second }?.first
    }

    private fun score(q: String, qRelation: Set<String>?, name: String): Double {
        if (name.isEmpty()) return 0.0
        if (name == q) return 1.0
        val tokens = name.split(" ")
        if (qRelation != null && tokens.any { it in qRelation }) return 0.97
        if (q in tokens || tokens.firstOrNull() == q.split(" ").first() && q.split(" ").size == 1) return 0.95
        val whole = similarity(q, name)
        val perToken = tokens.maxOf { similarity(q, it) } - 0.03
        return maxOf(whole, perToken)
    }

    /** 0-1 likeness of two normalized strings, by spelling and by sound, ignoring spaces ("whats up" ~ "whatsapp"). */
    fun similar(a: String, b: String): Double = similarity(a, b)

    private fun similarity(a: String, b: String): Double {
        val a1 = a.replace(" ", "")
        val b1 = b.replace(" ", "")
        return maxOf(ratio(a, b), ratio(phonetic(a), phonetic(b)), ratio(a1, b1), ratio(phonetic(a1), phonetic(b1)))
    }

    private fun ratio(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)

    /** Spelling-insensitive key for how a name sounds: "Ameet" ~ "Amit", "Shreya" ~ "Sreya", "Vikram" ~ "Wikram". */
    private fun phonetic(s: String): String {
        var t = s
        for ((from, to) in listOf("ee" to "i", "oo" to "u", "aa" to "a", "ph" to "f", "kh" to "k", "gh" to "g", "th" to "t",
            "dh" to "d", "bh" to "b", "sh" to "s", "w" to "v", "y" to "i")) t = t.replace(from, to)
        return t.replace(Regex("""(.)\1+"""), "$1")
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
