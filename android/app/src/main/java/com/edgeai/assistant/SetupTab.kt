package com.edgeai.assistant

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class PermRow(val perm: Perm, val title: String, val why: String, val settingsAction: String?)

private val PERM_ROWS = listOf(
    PermRow(Perm.CALLS, "Phone calls", "\"Call mom\" places the call directly", null),
    PermRow(Perm.CONTACTS, "Contacts", "Find who \"mom\" or \"Rahul\" is for calls and messages", null),
    PermRow(Perm.OVERLAY, "Display over other apps", "Lets voice commands open the clock, messages or apps while another app is on screen", Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    PermRow(Perm.EXACT_ALARMS, "Alarms & reminders", "Reminders fire at the exact minute", Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
    PermRow(Perm.DND, "Do Not Disturb access", "\"Turn on do not disturb\"", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
    // Samsung and vivo killed the listener in the background in the field logs ("KILL BACKGROUND").
    PermRow(Perm.BATTERY, "No battery restrictions", "Keeps \"Okay Edge\" listening when the app is in the background", Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
)

@Composable
fun SetupTab(
    showDebug: Boolean,
    onGrantRuntime: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onEnroll: () -> Unit,
    onNotWake: () -> Unit,
    onShareLogs: () -> Unit,
) {
    val perms by AssistantState.permissions.collectAsStateWithLifecycle()
    val status by AssistantState.status.collectAsStateWithLifecycle()
    val useBridge by AssistantState.useBridge.collectAsStateWithLifecycle()
    val recordSession by AssistantState.recordSession.collectAsStateWithLifecycle()
    val enrolling by AssistantState.enrolling.collectAsStateWithLifecycle()
    val enrollCount by AssistantState.enrollCount.collectAsStateWithLifecycle()
    val notWakeCount by AssistantState.notWakeCount.collectAsStateWithLifecycle()
    val active = status != Status.Stopped && status != Status.Error

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Permissions for actions", style = MaterialTheme.typography.titleMedium)
                for (row in PERM_ROWS) {
                    val ok = perms[row.perm] == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (ok) "✓ " else "✗ ") + row.title, color = if (ok) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurface)
                            Text(row.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!ok) {
                            OutlinedButton(onClick = { row.settingsAction?.let(onOpenSettings) ?: onGrantRuntime() }) { Text("Grant") }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Logs", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Everything you said, what the assistant did and replied, and timings. Shared as a .zip via WhatsApp, email or Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onShareLogs) { Text("Share logs") }
            }
        }

        if (showDebug) {
            Text("Debug", style = MaterialTheme.typography.titleMedium)
            DebugSwitch("Use laptop mic (adb bridge)", "Run prototype/mic_bridge.py. Applies on next Start.", useBridge, !active) {
                AssistantState.useBridge.value = it
            }
            DebugSwitch("Save session audio", "Records what the app hears, for tuning. Applies on next Start.", recordSession, !active) {
                AssistantState.recordSession.value = it
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wake word samples: $enrollCount / ${Enrollment.TARGET}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (active) "Stop listening first." else "Tap, wait for the beep, say \"Okay Edge\". ${Enrollment.hint(enrollCount)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onEnroll, enabled = !active && !enrolling) {
                        Text(if (enrolling) "Recording…" else "Record \"Okay Edge\" sample")
                    }
                    Text(
                        "NOT-wake samples: $notWakeCount / 15. Next, say: \"${Enrollment.notWakePrompt(notWakeCount)}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onNotWake, enabled = !active && !enrolling) { Text("Record NOT-wake sample") }
                }
            }
        }
    }
}

@Composable
private fun DebugSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
