package com.edgeai.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** [deferred]: the user still has to tap a notification to finish it (background activity-start limits). */
data class ActionResult(val ok: Boolean, val say: String, val deferred: Boolean = false)

/**
 * Carries out a parsed function call on the phone and returns what to say back.
 * Activity-based actions (alarm, SMS composer, open app) are subject to Android's background
 * activity-start limits: they run directly when the app is visible or may draw over other apps,
 * otherwise a tap-to-finish notification is posted.
 */
class ActionExecutor(private val context: Context) {

    fun execute(call: FunctionCall): ActionResult {
        val a = call.args.toMap()
        return try {
            when (call.name) {
                "set_alarm" -> setAlarm(a["time"], a["day"], a["label"])
                "set_timer" -> setTimer(a["duration"])
                "create_reminder" -> createReminder(a["text"], a["time"], a["day"])
                "call_contact" -> callContact(a["contact"])
                "send_message" -> sendMessage(a["contact"], a["message"])
                "set_flashlight" -> flashlight(a["state"] == "on")
                "set_volume" -> volume(a["action"])
                "set_do_not_disturb" -> doNotDisturb(a["state"] == "on")
                "media_control" -> media(a["action"])
                "open_app" -> openApp(a["app"])
                "dismiss_alarm" -> dismissAlarm(a["action"] == "snooze", a["time"])
                "play_media" -> playMedia(a["query"], a["app"])
                "web_search" -> webSearch(a["query"])
                "navigate" -> navigate(a["destination"])
                "search_maps" -> searchMaps(a["query"])
                "message_app" -> messageApp(a["app"], a["contact"], a["message"])
                "camera" -> camera(a["mode"])
                "search_app_store" -> searchAppStore(a["query"])
                // Not in the model's schema; CommandGuard routes "pause the timer" here.
                "show_timers" -> launch(Intent(AlarmClock.ACTION_SHOW_TIMERS), "Show timers", "Opening your timers.")
                else -> ActionResult(false, "I can't do that yet.")
            }
        } catch (e: SecurityException) {
            ActionResult(false, "I need a permission for that. Open Edge Assistant, Setup tab, to grant it.")
        } catch (e: Exception) {
            ActionResult(false, "Sorry, that didn't work.")
        }
    }

    // ---------------------------------------------------------------- clock
    private fun setAlarm(time: String?, day: String?, label: String?): ActionResult {
        if (time.isNullOrBlank()) return ActionResult(false, "What time should I set the alarm for?")
        val now = LocalDateTime.now()
        val t = TimeResolver.resolve(time, day, now) ?: return ActionResult(false, "Sorry, I didn't catch the time.")
        // Clock apps set the next occurrence of a time; beyond 24 hours the day has to be picked in the app.
        val silent = Duration.between(now, t) <= Duration.ofHours(24)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, silent)
        label?.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.replaceFirstChar { c -> c.uppercase() }) }
        val whenText = TimeResolver.describe(t, now)
        return launch(intent, "Set alarm for $whenText", if (silent) "Alarm set for $whenText." else "Opening the clock to set the alarm for $whenText.")
    }

    private fun setTimer(duration: String?): ActionResult {
        val seconds = duration?.let { SpokenTime.parseDuration(it) }?.takeIf { it in 1..86_400 }
            ?: return ActionResult(false, "How long should the timer be?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, "Start a ${describeDuration(seconds)} timer", "Timer set for ${describeDuration(seconds)}.")
    }

    /** [time] (only from CommandGuard's "stop the 6 am alarm") picks that alarm; otherwise the next one. */
    private fun dismissAlarm(snooze: Boolean, time: String?): ActionResult {
        if (snooze) return launch(Intent(AlarmClock.ACTION_SNOOZE_ALARM), "Snooze the alarm", "Snoozed.")
        val t = time?.let { TimeResolver.resolve(it, null, LocalDateTime.now()) }
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        if (t == null) {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            return launch(intent, "Dismiss the alarm", "Alarm dismissed.")
        }
        intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_IS_PM, t.hour >= 12)
        val clock = TimeResolver.describe(t, LocalDateTime.now()).substringBefore(" tomorrow").substringBefore(" on ")
        return launch(intent, "Turn off the $clock alarm", "Turning off the $clock alarm.")
    }

    // ---------------------------------------------------------------- reminders
    private fun createReminder(text: String?, time: String?, day: String?): ActionResult {
        if (text.isNullOrBlank()) return ActionResult(false, "What should I remind you about?")
        val now = LocalDateTime.now()
        val at = when {
            !time.isNullOrBlank() -> TimeResolver.resolve(time, day, now) ?: return ActionResult(false, "Sorry, I didn't catch the time.")
            else -> TimeResolver.date(day, now)?.atTime(9, 0)?.takeIf { it.isAfter(now) } ?: now.plusHours(1)
        }
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_TEXT, text).putExtra(ReminderReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val epoch = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, epoch, 5 * 60_000L, pi) // within 5 minutes without exact-alarm access
        }
        val what = text.removePrefix("to ").trim()
        val phrase = if (what.substringBefore(' ').lowercase() in ACTION_VERBS) "to $what" else "about $what"
        return ActionResult(true, "Okay, I'll remind you $phrase at ${TimeResolver.describe(at, now)}.")
    }

    // ---------------------------------------------------------------- phone
    private fun callContact(spoken: String?): ActionResult {
        if (spoken.isNullOrBlank()) return ActionResult(false, "Who should I call?")
        if (!granted(Manifest.permission.CALL_PHONE)) return ActionResult(false, "I need permission to make calls. Open the Setup tab to grant it.")
        val (name, number) = resolveNumber(spoken) ?: return ActionResult(false, "I couldn't find $spoken in your contacts.")
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        return ActionResult(true, "Calling $name.")
    }

    private fun sendMessage(spoken: String?, message: String?): ActionResult {
        if (spoken.isNullOrBlank()) return ActionResult(false, "Who should I message?")
        val resolved = resolveNumber(spoken)
        // Deliberately not sent silently: a misheard command must never text the wrong person.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${resolved?.second.orEmpty()}")).putExtra("sms_body", message.orEmpty())
        val who = resolved?.first ?: spoken
        return launch(intent, "Message $who", "Opening a message to $who. Tap send to confirm.")
    }

    /** (display name, number) for a spoken contact or phone number, or null. */
    private fun resolveNumber(spoken: String): Pair<String, String>? {
        ContactMatcher.asNumber(spoken)?.let { return it to it }
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val numbers = mutableMapOf<String, String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) numbers.putIfAbsent(c.getString(0) ?: continue, c.getString(1) ?: continue) }
        val name = ContactMatcher.best(spoken, numbers.keys) ?: return null
        return name to numbers.getValue(name)
    }

    // ---------------------------------------------------------------- device
    private fun flashlight(on: Boolean): ActionResult {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ActionResult(false, "This phone has no flashlight.")
        cm.setTorchMode(id, on)
        return ActionResult(true, if (on) "Flashlight on." else "Flashlight off.")
    }

    private fun volume(action: String?): ActionResult {
        val am = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val ui = AudioManager.FLAG_SHOW_UI
        return when (action) {
            "up" -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, ui); ActionResult(true, "Volume up.") }
            "down" -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, ui); ActionResult(true, "Volume down.") }
            "max" -> { am.setStreamVolume(stream, am.getStreamMaxVolume(stream), ui); ActionResult(true, "Volume at maximum.") }
            "mute" -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, ui)
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
                ActionResult(true, "Muted.")
            }
            "unmute" -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, ui)
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                ActionResult(true, "Sound on.")
            }
            "vibrate" -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; ActionResult(true, "Vibrate mode on.") }
            else -> ActionResult(false, "I didn't get what to do with the volume.")
        }
    }

    private fun doNotDisturb(on: Boolean): ActionResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult(false, "I need Do Not Disturb access. Open the Setup tab to grant it.")
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ActionResult(true, if (on) "Do not disturb is on." else "Do not disturb is off.")
    }

    private fun media(action: String?): ActionResult {
        val (key, say) = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Next track."
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Previous track."
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP to "Stopped."
            else -> return ActionResult(false, "I didn't get what to do with the music.")
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ActionResult(true, say)
    }

    private fun openApp(spoken: String?): ActionResult {
        if (spoken.isNullOrBlank()) return ActionResult(false, "Which app should I open?")
        val q = spoken.lowercase().removePrefix("the ").removeSuffix(" app").trim()
        val special = when (q) {
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "clock", "alarm", "alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "dialer", "phone" -> Intent(Intent.ACTION_DIAL)
            else -> null
        }
        if (special != null) return launch(special, "Open $q", "Opening $q.")
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val label = ContactMatcher.best(q, apps.keys) ?: return ActionResult(false, "I couldn't find an app called $spoken.")
        val intent = pm.getLaunchIntentForPackage(apps.getValue(label)) ?: return ActionResult(false, "I can't open $label.")
        return launch(intent, "Open $label", "Opening $label.")
    }

    // ---------------------------------------------------------------- inside other apps (DeepLinks)
    private fun playMedia(query: String?, spokenApp: String?): ActionResult {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return ActionResult(false, "What should I play?")
        val named = spokenApp?.takeIf { it.isNotBlank() }
        val app = named?.let(DeepLinks::mediaApp)
        if (named != null && app == null) {
            // An app without a known search link ("Hotstar"): open it rather than guess.
            return openApp(named).let { if (it.ok) it.copy(say = "I can't search inside $named yet, so I'm just opening it.") else it }
        }
        if (app != null && app != DeepLinks.MediaApp.YOUTUBE && !installed(app.pkg)) {
            return youtube(q, "${app.label} isn't installed, so here's $q on YouTube.")
        }
        val player = app ?: DeepLinks.PLAY_FROM_SEARCH_ORDER.firstOrNull { installed(it.pkg) && playFromSearch(it.pkg, q) != null }
        return when (player) {
            null, DeepLinks.MediaApp.YOUTUBE -> youtube(q, "Here's $q on YouTube.")
            else -> {
                val intent = playFromSearch(player.pkg, q) ?: when (player) {
                    DeepLinks.MediaApp.SPOTIFY -> view(DeepLinks.spotifySearch(q), player.pkg)
                    DeepLinks.MediaApp.YOUTUBE_MUSIC -> view(DeepLinks.youtubeMusicSearch(q), player.pkg)
                    else -> context.packageManager.getLaunchIntentForPackage(player.pkg)
                        ?: return ActionResult(false, "I can't open ${player.label}.")
                }
                launch(intent, "Play $q on ${player.label}", "Playing $q on ${player.label}.")
            }
        }
    }

    private fun youtube(query: String, say: String) =
        launch(view(DeepLinks.youtubeSearch(query), DeepLinks.MediaApp.YOUTUBE.pkg.takeIf(this::installed)), "Search YouTube for $query", say)

    /** The "play this" intent music apps take from assistants, if [pkg] handles it. */
    private fun playFromSearch(pkg: String, query: String): Intent? =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .takeIf { context.packageManager.resolveActivity(it, 0) != null }

    private fun webSearch(query: String?): ActionResult {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return ActionResult(false, "What should I search for?")
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        val intent = search.takeIf { context.packageManager.resolveActivity(it, 0) != null } ?: view(DeepLinks.googleSearch(q))
        return launch(intent, "Search for $q", "Searching for $q.")
    }

    private fun navigate(destination: String?): ActionResult {
        val d = destination?.trim()?.takeIf { it.isNotEmpty() } ?: return ActionResult(false, "Where to?")
        val intent = if (installed(DeepLinks.MAPS)) view(DeepLinks.navigation(d), DeepLinks.MAPS) else view(DeepLinks.mapSearch(d))
        return launch(intent, "Directions to $d", "Starting directions to $d.")
    }

    private fun searchMaps(query: String?): ActionResult {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return ActionResult(false, "What should I look for on the map?")
        return launch(view(DeepLinks.mapSearch(q), DeepLinks.MAPS.takeIf(this::installed)), "Find $q on the map", "Showing $q on the map.")
    }

    private fun messageApp(spokenApp: String?, spoken: String?, message: String?): ActionResult {
        if (spoken.isNullOrBlank()) return ActionResult(false, "Who should I message?")
        val app = DeepLinks.chatApp(spokenApp ?: "WhatsApp") ?: return ActionResult(false, "I can only write messages in WhatsApp for now.")
        val pkg = when {
            installed(app.pkg) -> app.pkg
            app == DeepLinks.ChatApp.WHATSAPP && installed(DeepLinks.ChatApp.WHATSAPP_BUSINESS.pkg) -> DeepLinks.ChatApp.WHATSAPP_BUSINESS.pkg
            else -> return ActionResult(false, "${app.label} isn't installed.")
        }
        if (app != DeepLinks.ChatApp.WHATSAPP && app != DeepLinks.ChatApp.WHATSAPP_BUSINESS) {
            // Telegram and Signal have no link that opens a chat by phone number.
            val open = context.packageManager.getLaunchIntentForPackage(pkg) ?: return ActionResult(false, "I can't open ${app.label}.")
            return launch(open, "Open ${app.label}", "I can't write messages in ${app.label} yet, so I'm just opening it.")
        }
        val (name, number) = resolveNumber(spoken) ?: return ActionResult(false, "I couldn't find $spoken in your contacts.")
        val country = context.getSystemService(TelephonyManager::class.java)?.let { it.simCountryIso.ifBlank { it.networkCountryIso } }
        val international = DeepLinks.whatsAppNumber(number, country) ?: return ActionResult(false, "I couldn't tell which country $name's number is from.")
        val say = if (message.isNullOrBlank()) "Opening WhatsApp chat with $name." else "Opening WhatsApp with a message to $name. Tap send to confirm."
        return launch(view(DeepLinks.whatsApp(international, message), pkg), "WhatsApp $name", say)
    }

    private fun camera(mode: String?): ActionResult = when (mode) {
        "video" -> launch(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), "Open the video camera", "Opening the video camera.")
        "selfie" -> launch(
            // No standard extra picks the front lens; these are the ones common camera apps read.
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true),
            "Open the selfie camera", "Opening the selfie camera.",
        )
        else -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Open the camera", "Opening the camera.")
    }

    private fun searchAppStore(query: String?): ActionResult {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return ActionResult(false, "Which app should I look for?")
        val intent = if (installed(DeepLinks.PLAY_STORE)) view(DeepLinks.playStoreSearch(q), DeepLinks.PLAY_STORE) else view(DeepLinks.playStoreWebSearch(q))
        return launch(intent, "Search the Play Store for $q", "Searching the Play Store for $q.")
    }

    private fun installed(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun view(uri: String, pkg: String? = null) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { if (pkg != null) setPackage(pkg) }

    // ---------------------------------------------------------------- helpers
    private fun launch(intent: Intent, description: String, say: String): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (AssistantState.appVisible.value || Settings.canDrawOverlays(context)) {
            context.startActivity(intent)
            return ActionResult(true, say)
        }
        // Android blocks activity starts from the background: hand the user a one-tap notification instead.
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(TAP_CHANNEL, "Finish an action", NotificationManager.IMPORTANCE_HIGH))
        val id = (System.nanoTime() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, NotificationCompat.Builder(context, TAP_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(description)
            .setContentText("Tap to finish. Allow \"Display over other apps\" to skip this step.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build())
        return ActionResult(true, "$description: tap the notification to finish.", deferred = true)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAP_CHANNEL = "tap_to_finish"
        private val ACTION_VERBS = setOf(
            "buy", "call", "take", "pay", "go", "submit", "water", "pick", "book", "check", "drink", "charge", "renew", "attend",
            "send", "feed", "visit", "collect", "recharge", "prepare", "wish", "finish", "order", "transfer", "lock", "meet",
            "get", "bring", "clean", "cook", "read", "study", "wake", "start", "stop", "turn", "switch", "email", "text", "reply",
        )

        fun describeDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it hour${if (it > 1) "s" else ""}" },
                m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
                s.takeIf { it > 0 }?.let { "$it second${if (it > 1) "s" else ""}" },
            ).joinToString(" ")
        }
    }
}
