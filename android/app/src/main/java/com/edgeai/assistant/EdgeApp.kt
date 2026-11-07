package com.edgeai.assistant

import android.app.Application

/** Installs crash capture before anything else runs, and notes why the previous run ended. */
class EdgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogs.init(this)
        AppLogs.installCrashHandler()
        Prefs.init(this)
        AssistantState.lastExit.value = AppLogs.recordExitReasons(this)
    }
}
