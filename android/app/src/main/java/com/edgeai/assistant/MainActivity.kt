package com.edgeai.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ACTION_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        refreshPermissions()
        val wantedStart = startAfterGrant
        startAfterGrant = false
        if (!wantedStart) {
            // Permissions asked from the Setup tab or for enrollment: nothing to start.
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(this, Intent(this, ListenerService::class.java))
        } else {
            Toast.makeText(this, "Microphone permission is needed to listen", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLogs.init(this)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainScreen(
                    voice = { VoiceTab(onStart = ::start, onStop = ::stop) },
                    chat = {
                        ChatTab(
                            onLoadModel = { LlmServer.start(this) },
                            onUnloadModel = { LlmServer.stop() },
                            onSend = { text ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    CommandRunner.run(this@MainActivity, text, fromVoice = false, execute = LlmChat.runTypedActions.value)
                                }
                            },
                        )
                    },
                    setup = {
                        SetupTab(
                            showDebug = debuggable,
                            onGrantRuntime = { requestPermissions.launch(ACTION_PERMISSIONS) },
                            onOpenSettings = { action -> openSettings(action) },
                            onEnroll = ::enroll,
                            onNotWake = ::enrollNotWake,
                            onShareLogs = { AppLogs.share(this) },
                        )
                    },
                )
            }
        }
    }

    private var startAfterGrant = false

    private fun start() {
        startAfterGrant = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            addAll(ACTION_PERMISSIONS)
        }
        requestPermissions.launch(permissions.toTypedArray())
    }

    private fun openSettings(action: String) {
        val intent = Intent(action)
        if (action != Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(action)) }
    }

    private fun refreshPermissions() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val granted = { p: String -> checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        AssistantState.permissions.value = mapOf(
            Perm.CALLS to granted(Manifest.permission.CALL_PHONE),
            Perm.CONTACTS to granted(Manifest.permission.READ_CONTACTS),
            Perm.OVERLAY to Settings.canDrawOverlays(this),
            Perm.EXACT_ALARMS to (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()),
            Perm.DND to nm.isNotificationPolicyAccessGranted,
            Perm.BATTERY to getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    override fun onStart() {
        super.onStart()
        AssistantState.appVisible.value = true
    }

    override fun onStop() {
        AssistantState.appVisible.value = false
        super.onStop()
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
        refreshPermissions()
    }

    private fun stop() {
        stopService(Intent(this, ListenerService::class.java))
    }

    override fun onDestroy() {
        if (isFinishing) LlmServer.stop()
        super.onDestroy()
    }
}

@Composable
private fun MainScreen(voice: @Composable () -> Unit, chat: @Composable () -> Unit, setup: @Composable () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Edge Assistant",
                Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Voice") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Chat") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Setup") })
            }
            when (tab) {
                0 -> voice()
                1 -> chat()
                else -> setup()
            }
        }
    }
}

@Composable
private fun VoiceTab(onStart: () -> Unit, onStop: () -> Unit) {
    val status by AssistantState.status.collectAsStateWithLifecycle()
    val log by AssistantState.log.collectAsStateWithLifecycle()
    val level by AssistantState.level.collectAsStateWithLifecycle()
    val active = status != Status.Stopped && status != Status.Error

    val lastExit by AssistantState.lastExit.collectAsStateWithLifecycle()
    run {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            lastExit?.let { reason ->
                Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Edge Assistant stopped unexpectedly last time ($reason).", style = MaterialTheme.typography.bodyMedium)
                        Text("Setup tab → Share logs to send the details.", style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.TextButton(onClick = { AssistantState.lastExit.value = null }) { Text("Dismiss") }
                    }
                }
            }
            StatusCard(status)
            LinearProgressIndicator(
                progress = { if (active) level else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                drawStopIndicator = {},
            )
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
