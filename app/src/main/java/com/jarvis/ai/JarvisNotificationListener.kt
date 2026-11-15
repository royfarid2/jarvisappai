package com.jarvis.ai

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Only active after the user grants Notification Access in Android settings. */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    fun summaries(): List<String> {
        val pm = packageManager
        return (activeNotifications ?: emptyArray())
            .filter { it.packageName != packageName && it.isClearable }
            .map { sbn ->
                val ex = sbn.notification.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
                catch (e: Exception) { sbn.packageName }
                listOf(app, listOf(title, text).filter { it.isNotBlank() }.joinToString(" - "))
                    .filter { it.isNotBlank() }.joinToString(": ")
            }
    }

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}
