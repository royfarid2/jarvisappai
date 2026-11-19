package com.jarvis.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.ai.MainViewModel
import com.jarvis.ai.agent.JarvisAccessibilityService
import com.jarvis.ai.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: MainViewModel, s: Settings, openSettings: () -> Unit) {
    val msgs by vm.messages.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSession.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val busy by vm.busy.collectAsState()
    val speaking by vm.tts.speaking.collectAsState()
    val listening by vm.stt.listening.collectAsState()
    val level by vm.stt.level.collectAsState()
    val partial by vm.stt.partial.collectAsState()
    val toast by vm.toast.collectAsState()
    val agentOn by vm.agentMode.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var askMic by remember { mutableStateOf(false) }
    var askAgent by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleMic() else vm.say("Microphone permission denied. You can still type to me.")
    }
    fun onMic() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic()
        else askMic = true
    }
    if (askMic) AlertDialog(
        onDismissRequest = { askMic = false },
        title = { Text("Microphone access") },
        text = { Text("JARVIS uses the microphone only while you tap the mic button, to turn your speech into text. Nothing is recorded in the background.") },
        confirmButton = { TextButton({ askMic = false; micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
        dismissButton = { TextButton({ askMic = false }) { Text("Not now") } },
    )
    if (askAgent) AlertDialog(
        onDismissRequest = { askAgent = false },
        title = { Text("Enable JARVIS control") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "JARVIS reads your screen and taps, types and scrolls in other apps, only for tasks you give it. " +
                        "It never types passwords, asks before payments or deletions, and you can stop it from the notification. " +
                        "Screen text is sent to your AI provider to decide each step.\n\n" +
                        "Android blocks this for apps installed from an APK until you allow it:\n" +
                        "1. Tap \"App info\" > menu (⋮, top right) > Allow restricted settings. If ⋮ isn't there, first try enabling the service once, then come back.\n" +
                        "2. Tap \"Accessibility\" > JARVIS AI > turn on."
                )
            }
        },
        confirmButton = {
            TextButton({
                askAgent = false
                ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Accessibility") }
        },
        dismissButton = {
            TextButton({
                askAgent = false
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("App info") }
        },
    )
    renaming?.let { (id, title) ->
        var t by remember(id) { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameSession(id, t); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }

    val total = msgs.size + if (streaming != null) 1 else 0
    LaunchedEffect(total, streaming?.length) { if (total > 0) listState.scrollToItem(total - 1) }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); vm.clearToast() } }

    val status = when {
        listening -> "Listening…"
        agentOn && busy -> "Agent working…"
        busy && !streaming.isNullOrEmpty() -> "Responding…"
        busy -> "Thinking…"
        speaking -> "Speaking…"
        agentOn -> "Agent mode"
        s.endpoint.isBlank() -> "No AI endpoint configured"
        else -> s.model
    }
    val title = sessions.firstOrNull { it.id == currentId }?.title ?: "New chat"

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    JarvisLogo(logoSize = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("J.A.R.V.I.S.", style = MaterialTheme.typography.labelSmall, color = Cyan)
                }
                Button(
                    { vm.newChat(); scope.launch { drawer.close() } },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("New chat") }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(sessions, key = { it.id }) { se ->
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                .background(if (se.id == currentId) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { vm.openSession(se.id); scope.launch { drawer.close() } }
                                .padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(se.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = se.id to se.title })
                                    DropdownMenuItem({ Text("Delete") }, { menu = false; vm.deleteSession(se.id) })
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                TextButton({ scope.launch { drawer.close() }; openSettings() }, Modifier.padding(8.dp)) {
                    Icon(Icons.Filled.Settings, null); Spacer(Modifier.width(8.dp)); Text("Settings")
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            HudBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Chats", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    JarvisLogo(logoSize = 34.dp, active = listening || speaking || busy)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (agentOn) Cyan else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(openSettings) { Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (total == 0) {
                        EmptyState { vm.send(it) }
                    } else {
                        LazyColumn(
                            state = listState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(msgs.size) { i ->
                                val m = msgs[i]
                                Bubble(m.role, m.content, if (m.role == "assistant") ({ vm.replay(m.content) }) else null)
                            }
                            if (streaming != null) item { Bubble("assistant", streaming!!.ifEmpty { "…" }, null) }
                        }
                    }
                    toast?.let {
                        Surface(
                            Modifier.align(Alignment.TopCenter).padding(12.dp), shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp,
                        ) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (listening) {
                        Waveform(level.coerceAtLeast(0.15f), Modifier.padding(bottom = 4.dp))
                        Text(
                            partial.ifEmpty { "Go ahead, I'm listening." }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    } else if (speaking) {
                        Waveform(0.55f, Modifier.padding(bottom = 4.dp), color = ArcBlue)
                    }
                    if (speaking || busy) {
                        OutlinedButton({ vm.stopAll() }, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (busy) "Stop" else "Stop speaking")
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        FilledIconButton(
                            {
                                if (agentOn) vm.agentMode.value = false
                                else if (JarvisAccessibilityService.isEnabled(ctx)) vm.agentMode.value = true
                                else askAgent = true
                            },
                            Modifier.size(52.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (agentOn) Cyan else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (agentOn) MaterialTheme.colorScheme.onPrimary else Cyan,
                            ),
                        ) { Icon(Icons.Filled.Bolt, if (agentOn) "Agent mode on" else "Agent mode off") }
                        Spacer(Modifier.width(8.dp))
                        TextField(
                            input, { input = it }, Modifier.weight(1f),
                            placeholder = { Text(if (agentOn) "Tell JARVIS what to do on your phone" else "Message JARVIS (or ask it to do something)") }, maxLines = 5,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (input.isBlank()) {
                            FilledIconButton(
                                { onMic() }, Modifier.size(52.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop listening" else "Speak") }
                        } else {
                            FilledIconButton({ vm.send(input); input = "" }, Modifier.size(52.dp), enabled = !busy) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(role: String, text: String, onReplay: (() -> Unit)?) {
    if (role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp),
                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.35f)),
                modifier = Modifier.widthIn(max = 300.dp),
            ) { SelectionContainer { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) } }
        }
    } else {
        val isNote = role == "note"
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (isNote) MaterialTheme.colorScheme.error else Cyan, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (isNote) "NOTICE" else "JARVIS", style = MaterialTheme.typography.labelSmall, color = if (isNote) MaterialTheme.colorScheme.error else Cyan)
                Spacer(Modifier.weight(1f))
                if (onReplay != null) IconButton(onReplay, Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Replay, "Replay", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SelectionContainer { MarkdownText(text, Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun EmptyState(onSuggest: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        JarvisLogo(logoSize = 130.dp)
        Spacer(Modifier.height(24.dp))
        Text("At your service.", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        listOf("Write a Python script that renames files", "Play music on Spotify", "How many members are in my Discord server?", "What's on my screen?").forEach {
            Surface(
                Modifier.padding(vertical = 4.dp).clickable { onSuggest(it) },
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.25f)),
            ) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
