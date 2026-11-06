package com.edgeai.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseCallTest {
    @Test
    fun parsesEscapedAndPlainArguments() {
        val call = parseCall("<start_function_call>call:create_reminder{day:<escape>friday<escape>,hour:5,minute:16,text:<escape>Buy milk, eggs<escape>}")!!
        assertEquals("create_reminder", call.name)
        assertEquals(listOf("day" to "friday", "hour" to "5", "minute" to "16", "text" to "Buy milk, eggs"), call.args)
    }

    @Test
    fun parsesCallWithoutClosingBraceOrArgs() {
        assertEquals(FunctionCall("set_flashlight", listOf("state" to "on")), parseCall("call:set_flashlight{state:<escape>on<escape>"))
        assertEquals(FunctionCall("set_timer", emptyList()), parseCall("<start_function_call>call:set_timer{}"))
    }

    @Test
    fun severalCallsInOrder() {
        val raw = "<start_function_call>call:set_do_not_disturb{state:<escape>on<escape>}<end_function_call>" +
            "<start_function_call>call:set_alarm{time:<escape>6<escape>}<end_function_call>"
        assertEquals(
            listOf(FunctionCall("set_do_not_disturb", listOf("state" to "on")), FunctionCall("set_alarm", listOf("time" to "6"))),
            parseCalls(raw),
        )
        assertEquals(1, parseCalls("<start_function_call>call:set_flashlight{state:<escape>on<escape>}<end_function_call>").size)
        assertEquals(emptyList<FunctionCall>(), parseCalls("Sorry, I can't help with that."))
    }

    @Test
    fun plainTextIsNotACall() {
        assertNull(parseCall("I'm sorry, I cannot assist with that."))
    }
}
