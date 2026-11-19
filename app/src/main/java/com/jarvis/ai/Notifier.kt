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
    const val ID_AGENT = 4242
    const val ID_CONFIRM = 4243

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun action(ctx: Context, code: Int, act: String) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, com.jarvis.ai.agent.AgentActionReceiver::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Ongoing "working" notification with a Stop button, visible while another app is in front. */
    @SuppressLint("MissingPermission")
    fun agentStatus(ctx: Context, text: String): Boolean {
        if (!canNotify(ctx)) return false
        val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_ID).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("JARVIS is working").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Stop", action(ctx, 1, com.jarvis.ai.agent.AgentControl.ACTION_STOP)).build()
        NotificationManagerCompat.from(ctx).notify(ID_AGENT, n)
        return true
    }

    /** Heads-up Allow/Deny question for sensitive taps. Returns false if it could not be shown. */
    @SuppressLint("MissingPermission")
    fun askConfirm(ctx: Context, text: String): Boolean {
        if (!canNotify(ctx)) return false
        val n = NotificationCompat.Builder(ctx, JarvisApp.ALERT_CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("JARVIS needs your approval").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(60_000)
            .addAction(0, "Allow", action(ctx, 2, com.jarvis.ai.agent.AgentControl.ACTION_ALLOW))
            .addAction(0, "Deny", action(ctx, 3, com.jarvis.ai.agent.AgentControl.ACTION_DENY)).build()
        NotificationManagerCompat.from(ctx).notify(ID_CONFIRM, n)
        return true
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

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
