package com.jarvis.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    @SuppressLint("MissingPermission")
    fun post(ctx: Context, title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.nanoTime() % Int.MAX_VALUE).toInt(), n)
        return true
    }

    /** Schedules a local notification (inexact alarm: no special permission required). */
    fun schedule(ctx: Context, text: String, delayMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, ReminderReceiver::class.java).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.post(context, "JARVIS", intent.getStringExtra("text") ?: "Reminder")
    }
}
