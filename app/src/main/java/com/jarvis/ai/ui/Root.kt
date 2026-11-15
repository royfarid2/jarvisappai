package com.jarvis.ai.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.jarvis.ai.MainViewModel

@Composable
fun JarvisRoot(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val dark = s?.dark ?: true
    val view = LocalView.current
    SideEffect {
        val w = (view.context as Activity).window
        val c = WindowCompat.getInsetsController(w, view)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }
    JarvisTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val st = s
            when {
                st == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { JarvisLogo(logoSize = 96.dp, active = true) }
                !st.onboarded -> OnboardingScreen(vm)
                else -> {
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    BackHandler(showSettings) { showSettings = false }
                    Crossfade(showSettings, label = "screen") { settingsOpen ->
                        if (settingsOpen) SettingsScreen(vm, st) { showSettings = false }
                        else ChatScreen(vm, st) { showSettings = true }
                    }
                }
            }
        }
    }
}
