package com.edgeai.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/** Foreground service that owns the mic and runs [VoicePipeline] on a dedicated audio thread. */
class ListenerService : Service() {

    @Volatile private var running = false
    @Volatile private var recorder: SessionRecorder? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_NOT_STICKY
        startInForeground()
        running = true
        worker = thread(name = "voice-pipeline") { runPipeline() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker = null
        super.onDestroy()
    }

    private fun runPipeline() {
        AppLogs.init(this)
        val session = AssistantState.newSession()
        AssistantState.setStatus(Status.Loading)
        // Warm up the command model and the voice while the speech models load, so the first command is quick.
        LlmServer.start(this)
        Speaker.init(this)
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        var input: AudioInput? = null
        var pipeline: VoicePipeline? = null
        try {
            val loadStart = SystemClock.elapsedRealtime()
            pipeline = VoicePipeline(assets) { event -> onEvent(event, tone) }
            AssistantState.info(
                "Models loaded in ${SystemClock.elapsedRealtime() - loadStart} ms" +
                    if (pipeline.usingPersonalWakeModel) " (personal wake model)" else " (generic wake model)",
            )

            input = if (AssistantState.useBridge.value) {
                BridgeInput { AssistantState.info(it) }
            } else {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    error("Microphone permission not granted")
                }
                MicInput()
            }
            if (AssistantState.recordSession.value) {
                recorder = SessionRecorder(getExternalFilesDir("recordings")!!) { wav, events ->
                    runCatching {
                        copyToDownloads(this, wav, "sessions", "audio/wav")
                        copyToDownloads(this, events, "sessions", "text/plain")
                    }.onFailure { Log.e(TAG, "copy to Downloads failed", it) }
                }.also {
                    AssistantState.info("Recording session to ${it.wav.name}")
                }
            }
            AssistantState.setStatus(Status.WaitingForWake)

            val pcm = ShortArray(CHUNK)
            while (running) {
                val n = input.read(pcm)
                if (n == 0) continue
                recorder?.write(pcm, n)
                val samples = FloatArray(n) { pcm[it] / 32768f }
                AssistantState.setLevel(samples)
                pipeline.feed(samples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "pipeline failed", e)
            AssistantState.info("Error: ${e.message}")
            if (AssistantState.isCurrent(session)) AssistantState.setStatus(Status.Error)
            stopSelf()
        } finally {
            input?.close()
            recorder?.close()
            recorder = null
            pipeline?.release()
            tone.release()
            // A Stop quickly followed by Start leaves this thread finishing after the new one began;
            // only the newest session may report Stopped.
            if (AssistantState.isCurrent(session) && AssistantState.status.value != Status.Error) {
                AssistantState.setStatus(Status.Stopped)
                AssistantState.setLevel(FloatArray(0))
            }
        }
    }

    private fun onEvent(event: PipelineEvent, tone: ToneGenerator) {
        val now = System.currentTimeMillis()
        recorder?.event(
            when (event) {
                is PipelineEvent.Wake -> "wake ${event.keyword}"
                PipelineEvent.Transcribing -> "transcribing"
                is PipelineEvent.Command -> "command \"${event.text}\" heard=\"${event.heard}\" asr_ms=${event.asrMillis}"
                is PipelineEvent.Ignored -> "ignored \"${event.heard}\""
                PipelineEvent.Timeout -> "timeout"
            },
        )
        when (event) {
            is PipelineEvent.Wake -> {
                Log.i(TAG, "wake: ${event.keyword}")
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                AssistantState.add(LogEntry.Wake(now, event.keyword))
                AssistantState.setStatus(Status.Listening)
            }
            PipelineEvent.Transcribing -> AssistantState.setStatus(Status.Transcribing)
            is PipelineEvent.Command -> {
                Log.i(TAG, "command: \"${event.text}\" heard \"${event.heard}\" (${event.audioSeconds}s audio, ASR ${event.asrMillis} ms)")
                AssistantState.add(LogEntry.Command(now, event.text, event.audioSeconds, event.asrMillis))
                thread(name = "command") { CommandRunner.run(this, event.text, fromVoice = true, execute = true) }
                AssistantState.setStatus(Status.WaitingForWake)
            }
            is PipelineEvent.Ignored -> {
                Log.i(TAG, "ignored: \"${event.heard}\"")
                AppLogs.activity("ignored (no command in it): \"${event.heard}\"")
                AssistantState.setStatus(Status.Listening)
            }
            PipelineEvent.Timeout -> {
                Log.i(TAG, "timeout")
                AssistantState.info("No command heard")
                AssistantState.setStatus(Status.WaitingForWake)
            }
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice assistant", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Listening for \"Okay Edge\"")
            .setContentText("All speech is processed on this device")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    companion object {
        private const val TAG = "EdgeListener"
        private const val CHANNEL_ID = "listener"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = VoicePipeline.SAMPLE_RATE
        private const val CHUNK = SAMPLE_RATE / 10
    }
}
