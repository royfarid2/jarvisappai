package com.jarvis.ai.assist

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.ai.JarvisApp
import com.jarvis.ai.R

/** Shows the assistant panel over the current app (works from the background with "Display over other apps"). */
object AssistLauncher {
    @SuppressLint("MissingPermission")
    fun show(ctx: Context, resultOnly: Boolean = false, fromWake: Boolean = false) {
        val intent = Intent(ctx, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(AssistActivity.EXTRA_RESULT_ONLY, resultOnly)
            .putExtra(AssistActivity.EXTRA_FROM_WAKE, fromWake)
        var overlay: View? = null
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (Settings.canDrawOverlays(ctx)) {
            // a tiny invisible overlay window makes the background start legal on every Android version
            try {
                overlay = View(ctx)
                wm.addView(overlay, WindowManager.LayoutParams(
                    1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT))
            } catch (e: Exception) { overlay = null }
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            // blocked: leave a tappable notification instead
            val pi = PendingIntent.getActivity(ctx, 12, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, JarvisApp.ALERT_CHANNEL).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("JARVIS").setContentText("Tap to talk to JARVIS")
                .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(ctx).notify(4400, n) } catch (se: Exception) { }
        }
        overlay?.let { v -> Handler(Looper.getMainLooper()).postDelayed({ try { wm.removeView(v) } catch (e: Exception) { } }, 2500) }
    }
}
