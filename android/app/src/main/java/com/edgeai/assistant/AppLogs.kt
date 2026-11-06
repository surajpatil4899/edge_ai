package com.edgeai.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persistent logs a tester can send back without adb: activity.log (wakes, transcripts, actions,
 * errors) plus the chat log (every command, function call, action and reply), zipped and handed to
 * the Android share sheet.
 */
object AppLogs {
    private const val MAX_BYTES = 2_000_000L
    @Volatile private var dir: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    /** Java/Kotlin crashes: stack trace to crash.log, then Android's normal crash handling. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                File(dir, "crash.log").appendText("=== ${stamp.format(Date())} thread ${thread.name}\n$trace\n")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Everything else that ends the app (native crashes in the speech/LLM libraries, low-memory kills,
     * ANRs), from Android's own record of why the previous process died. Returns a one-line summary if the
     * last exit since the previous check was abnormal.
     */
    fun recordExitReasons(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val prefs = context.getSharedPreferences("app_logs", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).filter { it.timestamp > seen }
        if (exits.isEmpty()) return null
        prefs.edit().putLong("last_exit", exits.maxOf { it.timestamp }).apply()
        val f = File(dir, "exits.log")
        for (e in exits.sortedBy { it.timestamp }) {
            val reason = exitReason(e.reason)
            f.appendText("=== ${stamp.format(Date(e.timestamp))} $reason (${e.description ?: "no description"}) " +
                "process=${e.processName} importance=${e.importance} pss=${e.pss / 1024} MB rss=${e.rss / 1024} MB\n")
            if (e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { r -> f.appendText(r.readText().take(200_000) + "\n") } }
            }
        }
        val last = exits.maxBy { it.timestamp }
        val abnormal = last.reason in listOf(
            android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
            android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        )
        return if (abnormal) "${exitReason(last.reason)} at ${stamp.format(Date(last.timestamp))}" else null
    }

    private fun exitReason(code: Int) = when (code) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
        else -> "exit reason $code"
    }

    @Synchronized
    fun activity(line: String) {
        val d = dir ?: return
        val f = File(d, "activity.log")
        if (f.length() > MAX_BYTES) f.renameTo(File(d, "activity.1.log"))
        runCatching { f.appendText("${stamp.format(Date())}  $line\n") }
    }

    fun chatLog(context: Context) = File(LlmServer.modelDir(context), "chat_log.jsonl")

    /** Zips the logs with device info and opens the share sheet. */
    fun share(activity: Activity) {
        init(activity)
        val out = File(activity.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val name = "edge_logs_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}_${Build.MODEL.replace(' ', '_')}.zip"
        val zip = File(out, name)
        ZipOutputStream(zip.outputStream()).use { z ->
            fun add(entry: String, file: File) {
                if (!file.exists()) return
                z.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            add("crash.log", File(dir, "crash.log"))
            add("exits.log", File(dir, "exits.log"))
            add("chat_log.jsonl", chatLog(activity))
            add("activity.1.log", File(dir, "activity.1.log"))
            add("activity.log", File(dir, "activity.log"))
            z.putNextEntry(ZipEntry("device.txt"))
            z.write(deviceInfo(activity).toByteArray())
            z.closeEntry()
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Edge Assistant logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, "Share Edge Assistant logs"))
    }

    private fun deviceInfo(context: Context): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        val model = context.assets.list("models/llm")?.firstOrNull { it.endsWith(".gguf") } ?: "none"
        val wake = if (context.assets.list("")?.contains("wakeword.json") == true) "personal (wakeword.json)" else "generic spotter"
        return """
            exported:  ${stamp.format(Date())}
            phone:     ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
            android:   ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            abis:      ${Build.SUPPORTED_ABIS.joinToString()}
            memory:    ${(context.getSystemService(android.app.ActivityManager::class.java).let { am ->
                android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_048_576 })} MB total
            storage:   ${context.filesDir.usableSpace / 1_048_576} MB free
            app:       ${app.versionName} (updated ${stamp.format(Date(app.lastUpdateTime))})
            llm:       $model
            wake word: $wake
        """.trimIndent() + "\n"
    }
}
