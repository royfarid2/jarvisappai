package com.jarvis.ai.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jarvis.ai.JarvisApp
import com.jarvis.ai.MainViewModel
import com.jarvis.ai.ui.AssistPanel
import com.jarvis.ai.ui.JarvisTheme

/** The Google-Assistant-style bottom panel: transparent window, dimmed app behind, JARVIS on top. */
class AssistActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.assistStart(fromWake) else vm.say("Microphone permission denied. You can still type to me.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = (application as JarvisApp).core()
        HotwordService.pause()                       // free the microphone for speech recognition
        setContent {
            JarvisTheme(dark = true) { AssistPanel(vm, onClose = { finishAndRemoveTask() }) }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(i: Intent) {
        if (i.getBooleanExtra(EXTRA_RESULT_ONLY, false)) return        // just showing the result of an agent task
        fromWake = i.getBooleanExtra(EXTRA_FROM_WAKE, false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.assistStart(fromWake)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStart() { super.onStart(); JarvisApp.foreground = true }
    override fun onStop() { super.onStop(); JarvisApp.foreground = false }

    override fun onDestroy() {
        vm.assistEnd()
        HotwordService.resume()
        super.onDestroy()
    }

    private var fromWake = false
    companion object { const val EXTRA_RESULT_ONLY = "result_only"; const val EXTRA_FROM_WAKE = "from_wake" }
}
