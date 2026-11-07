package com.edgeai.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * First run: what the app is, the permissions it needs (and why, one group at a time), and how to talk to it.
 * Ends by turning the assistant on.
 */
@Composable
fun Onboarding(
    onRequestBasics: () -> Unit,
    onRequestActions: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val perms by AssistantState.permissions.collectAsStateWithLifecycle()
    val steps = 5
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(step, label = "onboarding step") { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s) {
                        0 -> Page(
                            "👋", "Meet Edge Assistant",
                            "A voice assistant that runs entirely on your phone. It sets alarms and reminders, calls and messages " +
                                "your contacts, plays music, opens Maps, and more.",
                        ) {
                            Point("Private", "What you say never leaves your phone. No account needed, and it works offline (only playing YouTube looks the video up online).")
                            Point("A test version", "It will make mistakes. Your feedback on them is exactly what we need.")
                        }
                        1 -> Page("🎙️", "Microphone and notifications", "Needed to hear you, and for reminders and the \"listening\" notification.") {
                            PermissionList({ onRequestBasics() }, onOpenSettings, PERM_ROWS.filter { it.perm in setOf(Perm.MIC, Perm.NOTIFICATIONS) })
                        }
                        2 -> Page("📇", "Calls and contacts", "So \"call mom\" or \"text Rahul\" finds the right person. You can skip this and allow it later.") {
                            PermissionList({ onRequestActions() }, onOpenSettings, PERM_ROWS.filter { it.perm in setOf(Perm.CALLS, Perm.CONTACTS) })
                        }
                        3 -> Page("⚙️", "A few more switches", "Each opens a settings page. Allow it there, then come back. All optional.") {
                            PermissionList({}, onOpenSettings, PERM_ROWS.filter { it.settingsAction != null })
                        }
                        else -> Page("🗣️", "How to talk to it", "") {
                            Point("1. Say \"Okay Edge\"", "Then wait for the short beep.")
                            Point("2. Say what you want", "\"Set an alarm for 6 am\", \"Play Arijit Singh on Spotify\", \"Call mom\".")
                            Point("Or tap the mic", "If \"Okay Edge\" doesn't respond, the big mic button always works. You can also type.")
                            Point("Thumbs up or down", "Under each reply. A thumbs-down tells us exactly what to fix.")
                        }
                    }
                }
            }
        }
        val micOk = perms[Perm.MIC] == true
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            when (step) {
                1 -> if (micOk) Button(onClick = { step++ }) { Text("Next") } else Button(onClick = onRequestBasics) { Text("Allow") }
                2 -> {
                    val done = perms[Perm.CALLS] == true && perms[Perm.CONTACTS] == true
                    if (!done) TextButton(onClick = { step++ }) { Text("Skip") }
                    if (done) Button(onClick = { step++ }) { Text("Next") } else Button(onClick = onRequestActions) { Text("Allow") }
                }
                steps - 1 -> Button(onClick = onFinish, enabled = micOk) { Text("Start") }
                else -> Button(onClick = { step++ }) { Text(if (step == 0) "Get started" else "Next") }
            }
        }
    }
}

@Composable
private fun Page(emoji: String, title: String, body: String, content: @Composable () -> Unit) {
    Box(Modifier.size(84.dp).clip(CircleShape).background(Brand.warmGradient), contentAlignment = Alignment.Center) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
    }
    Text(title, style = MaterialTheme.typography.headlineMedium.copy(brush = Brand.primaryGradient), fontWeight = FontWeight.Bold)
    if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(4.dp))
    content()
}

@Composable
private fun Point(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
    }
}
