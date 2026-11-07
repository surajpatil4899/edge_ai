package com.edgeai.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val BASIC_PERMISSIONS = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
private val ACTION_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

class MainActivity : ComponentActivity() {

    /** The mic button was pressed before the microphone was allowed: listen once it is. */
    private var listenAfterGrant = false

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (listenAfterGrant) {
            listenAfterGrant = false
            if (micGranted()) listenNow() else Toast.makeText(this, "The microphone is needed to hear you", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLogs.init(this)
        Prefs.init(this)
        refreshPermissions()
        // Opening the app turns the assistant on; typed commands need the model even when listening is off.
        if (Prefs.onboarded.value) {
            LlmServer.start(this)
            if (micGranted()) startAssistant()
        }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        runDebugCommand(intent)
        setContent {
            EdgeTheme {
                val onboarded by Prefs.onboarded.state.collectAsStateWithLifecycle()
                if (!onboarded) {
                    Onboarding(
                        onRequestBasics = { requestPermissions.launch(BASIC_PERMISSIONS) },
                        onRequestActions = { requestPermissions.launch(ACTION_PERMISSIONS) },
                        onOpenSettings = ::openSettings,
                        onFinish = {
                            Prefs.set(Prefs.onboarded, true)
                            AppLogs.activity("onboarding finished: ${AssistantState.permissions.value.filterValues { it }.keys}")
                            LlmServer.start(this)
                            startAssistant()
                        },
                    )
                } else {
                    Home(debuggable)
                }
            }
        }
    }

    @Composable
    private fun Home(debuggable: Boolean) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(painterResource(R.drawable.ic_chat), null) }, label = { Text("Assistant") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(painterResource(R.drawable.ic_help), null) }, label = { Text("What to say") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(painterResource(R.drawable.ic_settings), null) }, label = { Text("Settings") })
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> AssistantScreen(onMic = ::onMic, onSend = ::send, onOpenHelp = { tab = 1 }, onShareLogs = { AppLogs.share(this@MainActivity) })
                    1 -> HelpScreen(onTry = { text -> tab = 0; send(text) })
                    else -> SettingsScreen(
                        showDebug = debuggable,
                        onGrantRuntime = { requestPermissions.launch(BASIC_PERMISSIONS + ACTION_PERMISSIONS) },
                        onOpenSettings = ::openSettings,
                        onStart = ::startAssistant,
                        onStop = ::stopAssistant,
                        onReport = ::report,
                        onShareLogs = { AppLogs.share(this@MainActivity) },
                        onEnroll = ::enroll,
                        onNotWake = ::enrollNotWake,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runDebugCommand(intent)
    }

    /**
     * Debug builds: `adb shell am start -n com.edgeai.assistant/.MainActivity --es edge_command "play X on spotify"`
     * runs a command as if typed, so actions can be tested on a phone without touching its screen.
     */
    private fun runDebugCommand(intent: Intent?) {
        val text = intent?.getStringExtra("edge_command") ?: return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent.removeExtra("edge_command")
        AppLogs.activity("debug command over adb: \"$text\"")
        lifecycleScope.launch(Dispatchers.IO) { CommandRunner.run(this@MainActivity, text, fromVoice = false, execute = true) }
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startAssistant() {
        if (micGranted()) ContextCompat.startForegroundService(this, Intent(this, ListenerService::class.java))
        else requestPermissions.launch(BASIC_PERMISSIONS)
    }

    private fun stopAssistant() {
        stopService(Intent(this, ListenerService::class.java))
    }

    /** Mic button: listen for one command now, starting the assistant first if it is off. */
    private fun onMic() {
        if (!micGranted()) {
            listenAfterGrant = true
            requestPermissions.launch(BASIC_PERMISSIONS)
            return
        }
        listenNow()
    }

    private fun listenNow() {
        val status = AssistantState.status.value
        if (status == Status.Stopped || status == Status.Error) startAssistant()
        // Picked up by the pipeline as soon as it is waiting (right away, or once the models have loaded).
        if (status != Status.Listening && status != Status.Transcribing) AssistantState.manualTrigger.set(true)
    }

    private fun send(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            CommandRunner.run(this@MainActivity, text, fromVoice = false, execute = LlmChat.runTypedActions.value)
        }
    }

    private fun report(note: String) {
        AppLogs.activity("TESTER REPORT: ${note.ifBlank { "(no description)" }}")
        AppLogs.share(this)
    }

    private fun openSettings(action: String) {
        val intent = Intent(action)
        if (action != Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(Intent(action)) } }
    }

    private fun refreshPermissions() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val granted = { p: String -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        AssistantState.permissions.value = mapOf(
            Perm.MIC to granted(Manifest.permission.RECORD_AUDIO),
            Perm.NOTIFICATIONS to (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)),
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

    override fun onResume() {
        super.onResume()
        AssistantState.enrollCount.value = Enrollment.count(this)
        AssistantState.notWakeCount.value = Enrollment.notWakeCount(this)
        refreshPermissions()
    }

    private fun enroll() {
        if (!micGranted()) requestPermissions.launch(BASIC_PERMISSIONS)
        else Enrollment.record(this) { AssistantState.enrollCount.value = it }
    }

    private fun enrollNotWake() {
        if (!micGranted()) requestPermissions.launch(BASIC_PERMISSIONS)
        else Enrollment.record(this, notWake = true) { AssistantState.notWakeCount.value = it }
    }

    override fun onDestroy() {
        if (isFinishing) LlmServer.stop()
        super.onDestroy()
    }
}
