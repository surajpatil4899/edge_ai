package com.edgeai.assistant

/** What testers can say, grouped for the Help tab; the first of each group doubles as a suggestion chip. */
data class ExampleGroup(val emoji: String, val title: String, val note: String?, val examples: List<String>)

val EXAMPLE_GROUPS = listOf(
    ExampleGroup("⏰", "Alarms and timers", null, listOf(
        "Set an alarm for 6 am tomorrow", "Wake me up in 2 hours", "Set a timer for 10 minutes", "Stop the 7 am alarm", "Snooze the alarm",
    )),
    ExampleGroup("📝", "Reminders", "You get a notification and hear it at the time.", listOf(
        "Remind me to buy milk at 5 pm", "Remind me to call mom in 30 minutes", "Remind me that I have a doctor's appointment tomorrow at 9 am",
    )),
    ExampleGroup("📞", "Calls and messages", "Uses your contacts. Messages always wait for you to tap send.", listOf(
        "Call mom", "Text Rahul I'm on my way", "Open WhatsApp and message Rahul I'm running late",
    )),
    ExampleGroup("🎵", "Music and videos", null, listOf(
        "Play Arijit Singh on Spotify", "Open YouTube and play bollywood songs", "Pause the music", "Next song",
    )),
    ExampleGroup("🔦", "Phone controls", null, listOf(
        "Turn on the flashlight", "Volume up", "Put my phone on vibrate", "Turn on do not disturb",
    )),
    ExampleGroup("🗺️", "Maps and search", null, listOf(
        "Navigate to the airport", "Find petrol pumps near me", "Search Google for the weather in Pune", "Search the Play Store for Swiggy",
    )),
    ExampleGroup("📸", "Apps and camera", null, listOf("Open WhatsApp", "Take a selfie", "Record a video")),
    ExampleGroup("✨", "Two things at once", "Join two requests with \"and\".", listOf(
        "Turn on do not disturb and set an alarm for 6 am", "Turn on the flashlight and set a timer for 5 minutes",
    )),
)

val SUGGESTIONS = listOf(
    "⏰" to "Set an alarm for 6 am tomorrow", "🔦" to "Turn on the flashlight", "🎵" to "Play Arijit Singh on YouTube",
    "🧭" to "Navigate to the airport", "💡" to "What can you do",
)
