package com.edgeai.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            ContextCompat.startForegroundService(this, Intent(this, ListenerService::class.java))
        } else {
            Toast.makeText(this, "Microphone permission is needed to listen", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AssistantScreen(showBridgeSwitch = debuggable, onStart = ::start, onStop = ::stop, onEnroll = ::enroll, onNotWake = ::enrollNotWake)
            }
        }
    }

    private fun start() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions.launch(permissions.toTypedArray())
    }

    private fun enroll() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        Enrollment.record(this) { AssistantState.enrollCount.value = it }
    }

    private fun enrollNotWake() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        Enrollment.record(this, notWake = true) { AssistantState.notWakeCount.value = it }
    }

    override fun onResume() {
        super.onResume()
        AssistantState.enrollCount.value = Enrollment.count(this)
        AssistantState.notWakeCount.value = Enrollment.notWakeCount(this)
    }

    private fun stop() {
        stopService(Intent(this, ListenerService::class.java))
    }
}

@Composable
private fun AssistantScreen(
    showBridgeSwitch: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnroll: () -> Unit,
    onNotWake: () -> Unit,
) {
    val status by AssistantState.status.collectAsStateWithLifecycle()
    val log by AssistantState.log.collectAsStateWithLifecycle()
    val level by AssistantState.level.collectAsStateWithLifecycle()
    val useBridge by AssistantState.useBridge.collectAsStateWithLifecycle()
    val recordSession by AssistantState.recordSession.collectAsStateWithLifecycle()
    val enrolling by AssistantState.enrolling.collectAsStateWithLifecycle()
    val enrollCount by AssistantState.enrollCount.collectAsStateWithLifecycle()
    val notWakeCount by AssistantState.notWakeCount.collectAsStateWithLifecycle()
    val active = status != Status.Stopped && status != Status.Error

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Edge Assistant", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            StatusCard(status)
            LinearProgressIndicator(
                progress = { if (active) level else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                drawStopIndicator = {},
            )
            if (showBridgeSwitch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use laptop mic (adb bridge)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Debug only. Run prototype/mic_bridge.py. Applies on next Start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useBridge, onCheckedChange = { AssistantState.useBridge.value = it }, enabled = !active)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save session audio", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Debug only. Records what the app hears, for tuning. Applies on next Start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = recordSession, onCheckedChange = { AssistantState.recordSession.value = it }, enabled = !active)
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
                        OutlinedButton(onClick = onNotWake, enabled = !active && !enrolling) {
                            Text("Record NOT-wake sample")
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active) OutlinedButton(onClick = onStop) { Text("Stop") } else Button(onClick = onStart) { Text("Start listening") }
                OutlinedButton(
                    onClick = { AssistantState.manualTrigger.set(true) },
                    enabled = status == Status.WaitingForWake,
                ) { Text("Tap to speak") }
            }
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(log, key = { "${it.time}-${it.hashCode()}" }) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun StatusCard(status: Status) {
    val (title, detail, color) = when (status) {
        Status.Stopped -> Triple("Stopped", "Tap Start to listen for the wake word", Color(0xFF9E9E9E))
        Status.Loading -> Triple("Loading models…", "Wake word, VAD and speech recognition", Color(0xFFFFA000))
        Status.WaitingForWake -> Triple("Say \"Okay Edge\"", "Then say your command", Color(0xFF43A047))
        Status.Listening -> Triple("Listening…", "Speak your command", Color(0xFF1E88E5))
        Status.Transcribing -> Triple("Transcribing…", "Running speech recognition on device", Color(0xFF8E24AA))
        Status.Error -> Triple("Error", "See the activity log below", Color(0xFFE53935))
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).background(color, CircleShape))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun LogRow(entry: LogEntry) {
    val time = timeFormat.format(Date(entry.time))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        when (entry) {
            is LogEntry.Wake -> Text(
                "$time  wake: ${if (entry.keyword == "manual") "button" else "\"Okay Edge\""}",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            is LogEntry.Command -> {
                Text("\"${entry.text.ifBlank { "(nothing recognised)" }}\"", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$time  %.1f s of audio, transcribed in %d ms".format(entry.audioSeconds, entry.asrMillis),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
            }
            is LogEntry.Info -> Text("$time  ${entry.message}", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}
