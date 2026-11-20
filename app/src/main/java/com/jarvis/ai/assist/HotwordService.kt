package com.jarvis.ai.assist

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.wakeword.SherpaWakeWord
import com.jarvis.ai.JarvisApp
import com.jarvis.ai.R

/**
 * Always-on "Jarvis" wake-word listener (Sherpa-ONNX keyword spotter, fully on-device, no account or key).
 * When it hears "Jarvis" it releases the microphone and shows the assistant panel over whatever app is open.
 */
class HotwordService : Service() {
    private var detector: SherpaWakeWord? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        showNotification()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        startDetection()
        return START_STICKY
    }

    private fun showNotification() {
        val ask = PendingIntent.getActivity(
            // from_wake = true: tapping the notification runs the SAME activation as hearing "Jarvis" (spoken greeting included)
            this, 10, Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AssistActivity.EXTRA_FROM_WAKE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 11, Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, JarvisApp.LISTEN_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("JARVIS is listening for \"Jarvis\"")
            .setContentText("Say \"Hey Jarvis\" from any app")
            .setOngoing(true).setSilent(true)
            .setContentIntent(ask)
            .addAction(0, "Wake JARVIS", ask)
            .addAction(0, "Turn off", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun startDetection() {
        if (detector != null) return
        val d = SherpaWakeWord(applicationContext, { main.post { onWake() } }, { msg -> lastError = msg })
        detector = d
        Thread {
            try { d.load(); lastError = null; d.start() }
            catch (e: Throwable) { lastError = e.message ?: e.javaClass.simpleName; main.post { stopSelf() } }
        }.start()
    }

    private fun onWake() {
        pauseDetection()
        AssistLauncher.show(applicationContext, fromWake = true)
    }

    fun pauseDetection() { try { detector?.stop() } catch (e: Exception) { } }
    fun resumeDetection() { try { detector?.start() } catch (e: Exception) { } }

    override fun onDestroy() {
        try { detector?.release() } catch (e: Exception) { }
        detector = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4300
        private const val ACTION_STOP = "com.jarvis.ai.HOTWORD_STOP"
        @Volatile var instance: HotwordService? = null
        @Volatile var lastError: String? = null

        fun isRunning() = instance != null

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, HotwordService::class.java)) }
            catch (e: Exception) { lastError = e.message }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, HotwordService::class.java)) }
        fun pause() { instance?.pauseDetection() }
        fun resume() { instance?.resumeDetection() }
    }
}
