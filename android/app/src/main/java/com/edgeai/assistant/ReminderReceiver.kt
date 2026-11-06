package com.edgeai.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Fires reminders scheduled by [ActionExecutor] with AlarmManager. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Reminder")
                .setContentText(text.replaceFirstChar { it.uppercase() })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
        Speaker.speak(context, "Reminder: $text")
    }

    companion object {
        const val CHANNEL = "reminders"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
