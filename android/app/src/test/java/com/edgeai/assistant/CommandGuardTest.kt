package com.edgeai.assistant

import com.edgeai.assistant.CommandGuard.Route
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cases are real transcripts and model outputs from the field logs (vivo V2045, Galaxy S25 Ultra, Nov 2026). */
class CommandGuardTest {
    private val enums = CommandGuard.enumsFrom(JSONArray(File("../../finetune/functions.json")  /* schema v3 */.readText()))

    private fun call(name: String, vararg args: Pair<String, String>) = FunctionCall(name, args.toList())
    private fun after(text: String, call: FunctionCall, single: Boolean = true) = CommandGuard.afterModel(text, call, enums, single)
    private fun ran(route: Route): FunctionCall = (route as Route.Call).call
    private fun answered(route: Route?) = assertTrue("expected an answer, got $route", route is Route.Answer)

    @Test
    fun noiseNeverReachesTheModel() {
        answered(CommandGuard.beforeModel("100000000000000000000000000000000000000000000000000000"))
        answered(CommandGuard.beforeModel("Conde"))
        answered(CommandGuard.beforeModel("Corned-head"))
        answered(CommandGuard.beforeModel(List(8) { "If you don't have a car, you can't do it." }.joinToString(" ")))
    }

    @Test
    fun realCommandsPassThrough() {
        for (text in listOf("Turn on flashlight", "Call it dead", "Volume up", "Pause", "Next", "Message shubham jio hi hello", "Set alarm after 6 hours")) {
            assertNull(text, CommandGuard.beforeModel(text))
        }
    }

    @Test
    fun helpQuestionsGetTheCapabilities() {
        for (text in listOf("What are the tasks you can", "Okay, Hg you may examples of class you can do locally", "What can you do")) {
            val r = CommandGuard.beforeModel(text)
            assertEquals(text, CommandGuard.CAPABILITIES, (r as Route.Answer).result.say)
        }
    }

    @Test
    fun questionsWithoutAFunctionBecomeASearch() {
        assertEquals(call("web_search", "query" to "what is the weather today"), ran(CommandGuard.noCall("What is the weather today")))
        assertEquals("web_search", ran(CommandGuard.noCall("Who is the prime minister of India?")).name)
        answered(CommandGuard.noCall("What are you doing"))                      // chit-chat
        answered(CommandGuard.noCall("How are you"))
        answered(CommandGuard.noCall("What is this? Tina. What is this? Tina is a very excellent"))  // background speech
        answered(CommandGuard.noCall("That's not plastic"))
        // phone, 7 Nov: abbreviations are not sentence breaks; the wake word's "Which" is not part of the query
        assertEquals("web_search", ran(CommandGuard.noCall("What happened to the recent u.s. metamolexia")).name)
        assertEquals(call("web_search", "query" to "what is the weather today"), ran(CommandGuard.noCall("Which what is the weather today")))
        assertEquals("web_search", ran(CommandGuard.noCall("Tell me about the Eiffel tower")).name)
        assertEquals("web_search", ran(CommandGuard.noCall("How to make masala chai")).name)
        answered(CommandGuard.noCall("Tell me a joke"))
        // phone, 7 Nov: ASR dropped "What"
        assertEquals(call("web_search", "query" to "happened to u. s. economy"), ran(CommandGuard.noCall("Happened to U. S. economy")))
        assertEquals("web_search", ran(CommandGuard.noCall("Cricket score today")).name)
        answered(CommandGuard.noCall("I checked my score"))                      // about the user
        answered(CommandGuard.noCall("Do it work"))
        assertEquals(CommandGuard.CAPABILITIES, (CommandGuard.beforeModel("What else can you do") as Route.Answer).result.say)
    }

    @Test
    fun timerControlOpensTimers() {
        assertEquals("show_timers", ((CommandGuard.beforeModel("Pause the timer")) as Route.Call).call.name)
    }

    @Test
    fun stopTheAlarmNeverSetsOne() {
        assertEquals(call("dismiss_alarm", "action" to "dismiss", "time" to "six am"), ran(after("Stop the alarm of six am", call("set_alarm", "time" to "six am"))))
        assertEquals(call("dismiss_alarm", "action" to "dismiss"), ran(after("It's top the alarm", call("set_alarm", "time" to "top"))))
        assertEquals(call("dismiss_alarm", "action" to "dismiss", "time" to "7 am"), ran(after("Cancel my 7 am alarm", call("set_alarm", "time" to "7 am"))))
        assertEquals("set_alarm", ran(after("Page set alarm for 6 am", call("set_alarm", "time" to "6 am"))).name)
    }

    @Test
    fun valuesOutsideTheSchemaAreRejected() {
        answered(after("Which make me a pet 6 am", call("set_volume", "action" to "6 am")))
        answered(after("Conde", call("media_control", "action" to "down")))
        assertEquals("set_volume", ran(after("Volume up", call("set_volume", "action" to "up"))).name)
    }

    @Test
    fun appNamesComeFromWhatWasSaid() {
        assertEquals(call("open_app", "app" to "what's up"), ran(after("Open what's up", call("open_app", "app" to "music media"))))
        assertEquals(call("open_app", "app" to "WhatsApp"), ran(after("Open whatsapp", call("open_app", "app" to "WhatsApp"))))
    }

    @Test
    fun barePlayWithAQueryBecomesASearch() {
        // What the v2 model returned in the field; v3 returns play_media itself.
        val play = call("media_control", "action" to "play")
        assertEquals(call("play_media", "query" to "little things", "app" to "spotify"), ran(after("1. Play little things on Spotify", play)))
        assertEquals(call("play_media", "query" to "any bollywood song", "app" to "youtube"), ran(after("Open youtube and play any bollywood song", play)))
        assertEquals(call("play_media", "query" to "some arijit singh songs"), ran(after("Play some arijit singh songs", play)))
        assertEquals(call("play_media", "query" to "kesariya"), ran(after("Volume up and play Kesariya and call dad", play, single = false)))
        assertEquals(play, ran(after("Play the music", play)))
        assertEquals(play, ran(after("Play", play)))
    }

    @Test
    fun deepLinkCallsAreNotRewritten() {
        val c = call("play_media", "query" to "bollywood songs", "app" to "YouTube")
        assertEquals(c, ran(after("Open YouTube and play bollywood songs", c)))
        val m = call("message_app", "app" to "WhatsApp", "contact" to "Rahul", "message" to "I'm late")
        assertEquals(m, ran(after("Open WhatsApp and message Rahul I'm late", m)))
        answered(after("Navigate to the airport", call("navigate", "destination" to "Lonavala")))   // not what was said
        answered(after("Take a photo", call("camera", "mode" to "panorama")))                      // not in the schema
    }

    @Test
    fun severalCallsAreCheckedOneByOne() {
        val text = "Stop the music and set an alarm for 6"
        assertEquals("set_alarm", ran(after(text, call("set_alarm", "time" to "6"), single = false)).name)
        assertEquals("media_control", ran(after(text, call("media_control", "action" to "stop"), single = false)).name)
        // "open X and ..." is only a composite to fix up when the model gave one call
        val flash = call("set_flashlight", "state" to "on")
        assertEquals(flash, ran(after("Open WhatsApp and turn on the flashlight", flash, single = false)))
    }

    @Test
    fun compositeOnlyAtTheStart() {
        val r = after("Remind me to open the door and lock it", call("create_reminder", "text" to "open the door and lock it"))
        assertEquals("create_reminder", ran(r).name)
    }

    @Test
    fun contactsAndTextMustBeSaid() {
        answered(after("Message myself hi", call("send_message", "contact" to "myself", "message" to "hi")))
        assertEquals("call_contact", ran(after("Calls and kit", call("call_contact", "contact" to "kits"))).name)
        assertEquals("call_contact", ran(after("Call it dead", call("call_contact", "contact" to "dead"))).name)
        answered(after("Turn it up", call("send_message", "contact" to "Rahul", "message" to "I will be late")))
    }

    @Test
    fun groundingToleratesAsrSpelling() {
        assertTrue(CommandGuard.grounded("Sima Moushi", "call sima moushi"))
        assertTrue(CommandGuard.grounded("I will be late", "send a message to rahul saying i'll be late"))
        assertTrue(CommandGuard.grounded("doctor's appointment", "remind me that i have doctors appointment tomorrow"))
    }
}
