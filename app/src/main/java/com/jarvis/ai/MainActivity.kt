package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.jarvis.ai.assist.HotwordService
import com.jarvis.ai.ui.JarvisRoot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm = (application as JarvisApp).core()
        setContent { JarvisRoot(vm) }
        // keep the wake-word listener alive across app restarts (a microphone service may only start while the app is visible)
        lifecycleScope.launch {
            val s = vm.settings.filterNotNull().first()
            if (s.hotword) HotwordService.start(this@MainActivity)
        }
    }

    override fun onStart() { super.onStart(); JarvisApp.foreground = true }
    override fun onStop() { super.onStop(); JarvisApp.foreground = false }
}
