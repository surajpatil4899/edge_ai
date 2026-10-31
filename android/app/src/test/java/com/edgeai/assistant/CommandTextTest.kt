package com.edgeai.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTextTest {
    @Test
    fun stripsWakePhrase() {
        assertEquals("turn on the flashlight", commandText("Hey edge turn on the flashlight"))
        assertEquals("Set an alarm for 6 am", commandText("Hey, Edge. Set an alarm for 6 am."))
        assertEquals("call mom", commandText("edge, call mom"))
        assertEquals("call mom", commandText("Hey it call mom"))
        assertEquals("set a timer for 5 minutes", commandText("Okay edge, set a timer for 5 minutes"))
        assertEquals("call mom", commandText("OK, Edge. call mom"))
        assertEquals("Set an alarm", commandText("Hey, Ed. Set an alarm."))
    }

    @Test
    fun keepsPlainCommands() {
        assertEquals("Set a timer for 10 minutes", commandText("Set a timer for 10 minutes."))
        assertEquals("Hey Siri what's up", commandText("Hey Siri what's up"))
    }

    @Test
    fun wakeTailBeepAndFillersAreNotCommands() {
        for (heard in listOf("", "Okay.", "OK, edge.", "Okay Edge.", "Edge.", "Hey Edge.", "He edge.", "Hey it.", "Hedge.", "Hey, Edge,", "Um.", "Mm.", "Hmm", "the", "Hey.", "Oh, uh.")) {
            assertEquals("for \"$heard\"", "", commandText(heard))
        }
    }
}
