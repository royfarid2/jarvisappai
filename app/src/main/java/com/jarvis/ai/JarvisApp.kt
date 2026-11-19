package com.jarvis.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.room.Room
import com.jarvis.ai.data.AppDb

class JarvisApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /** One assistant "brain" for the whole process, shared by the app UI and the Hey-JARVIS panel. */
    fun core(): MainViewModel =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MainViewModel::class.java]

    val db: AppDb by lazy { Room.databaseBuilder(applicationContext, AppDb::class.java, "jarvis.db").fallbackToDestructiveMigration().build() }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Reminders and task notifications" }
        )
        nm.createNotificationChannel(
            NotificationChannel(LISTEN_CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while JARVIS listens for its wake word" }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "JARVIS alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Agent status and confirmations" }
        )
    }

    companion object {
        const val CHANNEL_ID = "jarvis_main"
        const val ALERT_CHANNEL = "jarvis_alert"
        const val LISTEN_CHANNEL = "jarvis_listen"
        @Volatile var foreground = false
    }
}
