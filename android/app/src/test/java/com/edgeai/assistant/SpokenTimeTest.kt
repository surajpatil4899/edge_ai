package com.edgeai.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Same cases as finetune/spoken.py, so the app and the evaluation agree on every phrase. */
class SpokenTimeTest {
    private val cases = JSONObject(File("../../finetune/spoken_cases.json").readText())

    @Test
    fun times() {
        val failures = mutableListOf<String>()
        val arr: JSONArray = cases.getJSONArray("times")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONArray(i)
            val want = if (c.isNull(1)) null else c.getJSONArray(1).let { it.getInt(0) to it.getInt(1) }
            val got = SpokenTime.parseTime(c.getString(0))
            if (got != want) failures += "${c.getString(0)}: got $got want $want"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun durations() {
        val failures = mutableListOf<String>()
        val arr: JSONArray = cases.getJSONArray("durations")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONArray(i)
            val want = if (c.isNull(1)) null else c.getInt(1)
            val got = SpokenTime.parseDuration(c.getString(0))
            if (got != want) failures += "${c.getString(0)}: got $got want $want"
        }
        assertEquals(emptyList<String>(), failures)
    }
}
