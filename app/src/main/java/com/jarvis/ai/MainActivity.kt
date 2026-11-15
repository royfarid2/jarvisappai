package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.ui.JarvisRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            JarvisRoot(vm)
        }
    }

    override fun onStart() { super.onStart(); JarvisApp.foreground = true }
    override fun onStop() { super.onStop(); JarvisApp.foreground = false }
}
