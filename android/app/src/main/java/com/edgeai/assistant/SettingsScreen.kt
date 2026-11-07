package com.edgeai.assistant

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A permission an action needs: why, and how to grant it (runtime prompt, or a settings screen). */
data class PermRow(val perm: Perm, val title: String, val why: String, val settingsAction: String?)

val PERM_ROWS = listOf(
    PermRow(Perm.MIC, "Microphone", "To hear \"Okay Edge\" and your commands. Audio never leaves the phone.", null),
    PermRow(Perm.NOTIFICATIONS, "Notifications", "Reminders, and the \"listening\" notification Android requires", null),
    PermRow(Perm.CALLS, "Phone calls", "\"Call mom\" places the call directly", null),
    PermRow(Perm.CONTACTS, "Contacts", "Find who \"mom\" or \"Rahul\" is for calls and messages", null),
    PermRow(Perm.OVERLAY, "Display over other apps", "Lets commands open the clock, WhatsApp, Maps or YouTube while you're in another app", Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    PermRow(Perm.EXACT_ALARMS, "Alarms & reminders", "Reminders ring at the exact minute", Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
    PermRow(Perm.DND, "Do Not Disturb access", "\"Turn on do not disturb\"", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
    // Samsung and vivo killed the listener in the background in the field logs ("KILL BACKGROUND").
    PermRow(Perm.BATTERY, "No battery restrictions", "Keeps \"Okay Edge\" working when the app is in the background", Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
)

@Composable
fun SettingsScreen(
    showDebug: Boolean,
    onGrantRuntime: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReport: (String) -> Unit,
    onShareLogs: () -> Unit,
    onEnroll: () -> Unit,
    onNotWake: () -> Unit,
) {
    val status by AssistantState.status.collectAsStateWithLifecycle()
    val wakeWord by Prefs.wakeWord.state.collectAsStateWithLifecycle()
    val spoken by Prefs.spokenReplies.state.collectAsStateWithLifecycle()
    val details by Prefs.showDetails.state.collectAsStateWithLifecycle()
    val runTyped by LlmChat.runTypedActions.collectAsStateWithLifecycle()
    val server by LlmServer.state.collectAsStateWithLifecycle()
    val active = status != Status.Stopped && status != Status.Error
    var reporting by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall.copy(brush = Brand.primaryGradient), fontWeight = FontWeight.Bold)

        Section("🎙️  Assistant") {
            ToggleRow("Listen for \"Okay Edge\"", "Off: only the mic button starts a command", wakeWord) { Prefs.set(Prefs.wakeWord, it) }
            ToggleRow("Speak replies", "Replies are always shown on screen too", spoken) { Prefs.set(Prefs.spokenReplies, it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (active) "Assistant is on" else "Assistant is off", style = MaterialTheme.typography.bodyLarge)
                    Text("Uses the microphone while on; a notification shows it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active) OutlinedButton(onClick = onStop) { Text("Turn off") } else Button(onClick = onStart) { Text("Turn on") }
            }
        }

        Section("🔐  Permissions") { PermissionList(onGrantRuntime, onOpenSettings) }

        Section("🧪  Help us test") {
            Text(
                "Something wrong or confusing? Tell us what happened; the app's logs are attached so we can see exactly what it heard and did.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { reporting = true }) { Text("Report a problem") }
                OutlinedButton(onClick = onShareLogs) { Text("Share logs") }
            }
        }

        Section("🛡️  Privacy") {
            Text(
                "Wake word, speech recognition and the command model all run on this phone, and what you say is never sent anywhere. " +
                    "The one exception: to play something on YouTube, the app looks up that video on YouTube, as the YouTube app would. " +
                    "Logs stay on the phone unless you share them.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("🛠️  Developer") {
            ToggleRow("Show technical details", "Model output, timings and checks under each reply", details) { Prefs.set(Prefs.showDetails, it) }
            ToggleRow("Carry out typed commands", "Off: typed commands only show the function call", runTyped) { LlmChat.runTypedActions.value = it }
            Text(
                "Command model: " + when (val s = server) {
                    is LlmServer.State.Ready -> "${s.model} (loaded in ${s.loadMillis} ms)"
                    is LlmServer.State.Failed -> "failed: ${s.message}"
                    LlmServer.State.Starting -> "loading"
                    LlmServer.State.Stopped -> "not loaded"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showDebug) DebugTools(active, onEnroll, onNotWake)
        }
    }

    if (reporting) ReportDialog(onDismiss = { reporting = false }) { note ->
        reporting = false
        onReport(note)
    }
}

@Composable
fun PermissionList(onGrantRuntime: () -> Unit, onOpenSettings: (String) -> Unit, rows: List<PermRow> = PERM_ROWS) {
    val perms by AssistantState.permissions.collectAsStateWithLifecycle()
    val good = if (isSystemInDarkTheme()) StatusColors.goodDark else StatusColors.good
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            val ok = perms[row.perm] == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (ok) "✓  " else "") + row.title, style = MaterialTheme.typography.bodyLarge, color = if (ok) good else MaterialTheme.colorScheme.onSurface)
                    Text(row.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!ok) OutlinedButton(onClick = { row.settingsAction?.let(onOpenSettings) ?: onGrantRuntime() }) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What did you say, and what went wrong?", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("e.g. I said \"call papa\" and it called Pappu") })
            }
        },
        confirmButton = { TextButton(onClick = { onSend(note.trim()) }) { Text("Next: share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Debug builds only: laptop mic bridge, session recording, wake-word enrollment and the raw activity log. */
@Composable
private fun DebugTools(active: Boolean, onEnroll: () -> Unit, onNotWake: () -> Unit) {
    val useBridge by AssistantState.useBridge.collectAsStateWithLifecycle()
    val recordSession by AssistantState.recordSession.collectAsStateWithLifecycle()
    val enrolling by AssistantState.enrolling.collectAsStateWithLifecycle()
    val enrollCount by AssistantState.enrollCount.collectAsStateWithLifecycle()
    val notWakeCount by AssistantState.notWakeCount.collectAsStateWithLifecycle()
    val log by AssistantState.log.collectAsStateWithLifecycle()
    HorizontalDivider()
    ToggleRow("Use laptop mic (adb bridge)", "Run prototype/mic_bridge.py. Applies on next start.", useBridge, !active) { AssistantState.useBridge.value = it }
    ToggleRow("Save session audio", "Records what the app hears, for tuning. Applies on next start.", recordSession, !active) { AssistantState.recordSession.value = it }
    Text("Wake word samples: $enrollCount / ${Enrollment.TARGET}. " + if (active) "Turn the assistant off first." else Enrollment.hint(enrollCount),
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEnroll, enabled = !active && !enrolling) { Text(if (enrolling) "Recording…" else "\"Okay Edge\" sample") }
        OutlinedButton(onClick = onNotWake, enabled = !active && !enrolling) { Text("NOT-wake ($notWakeCount/15)") }
    }
    Text("Next NOT-wake phrase: \"${Enrollment.notWakePrompt(notWakeCount)}\"", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Activity log", style = MaterialTheme.typography.titleSmall)
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    log.take(40).forEach { e ->
        val line = when (e) {
            is LogEntry.Wake -> "wake: ${e.keyword}"
            is LogEntry.Command -> "heard \"${e.text}\" (%.1f s, ASR %d ms)".format(e.audioSeconds, e.asrMillis)
            is LogEntry.Info -> e.message
        }
        Text("${time.format(java.util.Date(e.time))}  $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
