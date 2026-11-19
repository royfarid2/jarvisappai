package com.jarvis.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.JarvisNotificationListener
import com.jarvis.ai.agent.JarvisAccessibilityService
import com.jarvis.ai.assist.HotwordService
import com.jarvis.ai.MainViewModel
import com.jarvis.ai.data.Settings

private val LANGS = listOf("en-GB" to "English (UK)", "ar-IQ" to "Arabic (Iraqi)", "ar-SA" to "Arabic (Saudi)")

@Composable
fun SettingsScreen(vm: MainViewModel, current: Settings, onBack: () -> Unit) {
    var d by remember { mutableStateOf(current) }
    val voices by vm.tts.voices.collectAsState()
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var askNotif by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val notifOn = remember(refresh) {
        refresh.let { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    }
    val listenerOn = remember(refresh) { refresh.let { JarvisNotificationListener.isEnabled(ctx) } }
    val a11yOn = remember(refresh) { refresh.let { JarvisAccessibilityService.isEnabled(ctx) } }
    val overlayOn = remember(refresh) { refresh.let { android.provider.Settings.canDrawOverlays(ctx) } }
    val micOn = remember(refresh) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear conversation history?") },
        text = { Text("This permanently deletes all messages stored on this device.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
    if (askNotif) AlertDialog(
        onDismissRequest = { askNotif = false },
        title = { Text("Allow notifications?") },
        text = { Text("JARVIS posts notifications for reminders you ask for and to tell you when a reply is ready while the app is in the background.") },
        confirmButton = {
            TextButton({
                askNotif = false
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Continue") }
        },
        dismissButton = { TextButton({ askNotif = false }) { Text("Not now") } },
    )

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button({ vm.saveSettings(d); onBack() }) { Text("Save") }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("AI connection")
            ConnectionFields(vm, d) { d = it }

            Section("Voice")
            SwitchRow("Speak responses", "JARVIS reads replies aloud as they appear", d.autoVoice) { d = d.copy(autoVoice = it) }
            SwitchRow("Hands-free mode", "After a spoken question and answer, keep listening", d.handsFree) { d = d.copy(handsFree = it) }
            Text("Backup on-device voice", style = MaterialTheme.typography.titleMedium)
            var menu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) {
                    Text(if (d.voiceName.isEmpty()) "Auto: best British male voice" else d.voiceName, maxLines = 1)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Auto: best British male voice") }, { d = d.copy(voiceName = ""); menu = false })
                    voices.forEach { v ->
                        DropdownMenuItem({ Text("${v.locale.toLanguageTag()}  ${v.name}") }, { d = d.copy(voiceName = v.name); menu = false })
                    }
                }
            }
            if (voices.none { it.locale.country == "GB" }) {
                Text(
                    "No British voice found. In Android Settings > System > Languages > Text-to-speech, install English (UK) voice data for the best result.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SliderRow("Speed", d.rate, 0.5f..1.5f) { d = d.copy(rate = it) }
            SliderRow("Pitch (lower = deeper)", d.pitch, 0.5f..1.5f) { d = d.copy(pitch = it) }

            Section("JARVIS voice (Fish Audio)")
            VoiceFields(d) { d = it }
            OutlinedButton({ vm.previewVoice(d) }, Modifier.fillMaxWidth()) { Text("Preview JARVIS voice") }

            Section("Hey JARVIS (wake word)")
            Text(
                "Say \"Hey Jarvis\" from any app and the assistant panel slides up over it, like Google Assistant. " +
                    "Detection runs on your phone. It needs a free Picovoice AccessKey from console.picovoice.ai.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var showPv by remember { mutableStateOf(false) }
            OutlinedTextField(
                d.picovoiceKey, { d = d.copy(picovoiceKey = it) }, Modifier.fillMaxWidth(),
                label = { Text("Picovoice AccessKey") }, singleLine = true,
                visualTransformation = if (showPv) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = { TextButton({ showPv = !showPv }) { Text(if (showPv) "Hide" else "Show") } },
            )
            SwitchRow("Listen for \"Jarvis\"", "Keeps a small notification while listening. Uses the microphone, so the first start must happen with the app open.", d.hotword) { d = d.copy(hotword = it) }
            PermRow(
                "Display over other apps", if (overlayOn) "Allowed" else "Required so the panel can appear over other apps",
                if (overlayOn) null else "Allow",
            ) { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PermRow(
                "Battery optimisation", "Set JARVIS to \"Unrestricted\" / not optimised, or the system may stop the listener.",
                "Open",
            ) { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (HotwordService.lastError != null) Text("Last wake-word error: " + HotwordService.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            Section("Language")
            Text("Speech recognition language (JARVIS always replies in English)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LANGS.forEach { (tag, label) ->
                Row(Modifier.fillMaxWidth().clickable { d = d.copy(language = tag) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(d.language == tag, { d = d.copy(language = tag) })
                    Text(label)
                }
            }

            Section("Appearance")
            SwitchRow("Dark theme", null, d.dark) { d = d.copy(dark = it) }

            Section("Permissions")
            PermRow("Microphone", if (micOn) "Granted. Used only while you tap the mic." else "Requested when you first tap the mic.", null, null)
            PermRow(
                "Notifications", if (notifOn) "Allowed" else "Needed for reminders and alerts",
                if (notifOn) null else "Allow",
            ) { askNotif = true }
            PermRow(
                "Notification access", if (listenerOn) "Enabled: JARVIS can read your notifications when you ask"
                else "Optional. Lets JARVIS read your notifications aloud on request. You grant it in Android settings.",
                if (listenerOn) "Manage" else "Open settings",
            ) { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PermRow(
                "Accessibility (Agent mode)",
                if (a11yOn) "Enabled: JARVIS can see the screen and operate apps when you give it a task in Agent mode"
                else "Optional. Lets JARVIS read your screen and tap, type and scroll for you. You grant it in Android settings (on Android 13+ first allow restricted settings in App info).",
                if (a11yOn) "Manage" else "Open settings",
            ) { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            SwitchRow("Use screenshots", "Lets JARVIS send a screenshot to your AI provider when text alone isn't enough (Android 11+). Off by default.", d.agentVision) { d = d.copy(agentVision = it) }
            if (d.agentVision) OutlinedTextField(d.visionModel, { d = d.copy(visionModel = it) }, Modifier.fillMaxWidth(),
                label = { Text("Vision model") }, singleLine = true, supportingText = { Text("Groq: qwen/qwen3.8-27b") })
            Text(
                "JARVIS never types passwords and asks before sensitive taps (send, pay, delete). Stop an agent task any time from its notification.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Data")
            OutlinedButton({ confirmClear = true }, Modifier.fillMaxWidth()) { Text("Delete all chats") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cyan)
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
private fun PermRow(title: String, sub: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null && onAction != null) OutlinedButton(onAction) { Text(action) }
    }
}
