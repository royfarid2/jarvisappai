package com.jarvis.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.jarvis.ai.data.AppDb

class JarvisApp : Application() {
    val db: AppDb by lazy { Room.databaseBuilder(applicationContext, AppDb::class.java, "jarvis.db").build() }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Reminders and task notifications" }
        )
    }

    companion object {
        const val CHANNEL_ID = "jarvis_main"
        @Volatile var foreground = false
    }
}
