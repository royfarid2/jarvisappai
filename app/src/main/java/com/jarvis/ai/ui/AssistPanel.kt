package com.jarvis.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.ai.MainViewModel

private val SUGGESTIONS = listOf(
    "What's on my screen?", "Play music on Spotify", "Open YouTube", "What's my battery?",
    "How many members are in my Discord server?", "Set an alarm for 7 AM",
)

/** Bottom sheet shown over any app when you say "Hey Jarvis". */
@Composable
fun AssistPanel(vm: MainViewModel, onClose: () -> Unit) {
    val msgs by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val busy by vm.busy.collectAsState()
    val speaking by vm.tts.speaking.collectAsState()
    val listening by vm.stt.listening.collectAsState()
    val level by vm.stt.level.collectAsState()
    val partial by vm.stt.partial.collectAsState()
    val agentRunning by vm.agentRunning.collectAsState()
    val toast by vm.toast.collectAsState()
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    // an agent task needs the screen free: step aside, JARVIS keeps working and reports back
    LaunchedEffect(agentRunning) { if (agentRunning) onClose() }

    val lastUser = msgs.lastOrNull { it.role == "user" }
    val lastReply = streaming ?: msgs.lastOrNull { it.id > (lastUser?.id ?: Long.MAX_VALUE) && it.role != "user" }?.content
    val muted by vm.assistMuted.collectAsState()
    val state = when {
        toast != null -> CoreState.ERROR
        listening -> CoreState.LISTENING
        speaking -> CoreState.SPEAKING
        busy -> CoreState.THINKING
        else -> CoreState.IDLE
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
    ) {
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.3f)),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            toast != null -> "Something went wrong"
                            muted -> "Microphone muted"
                            listening -> "Listening…"
                            speaking -> "Speaking…"
                            busy && streaming.isNullOrEmpty() -> "Thinking…"
                            lastUser == null -> "At your service."
                            else -> "Ready"
                        },
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { JarvisCore(state, level) }

                if (lastUser != null) {
                    Text(lastUser.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    if (!lastReply.isNullOrBlank()) {
                        Box(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            SelectionContainer { MarkdownText(lastReply) }
                        }
                    }
                } else if (!listening) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SUGGESTIONS) { q ->
                            Surface(
                                Modifier.clickable { vm.send(q, fromVoice = false) },
                                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.25f)),
                            ) { Text(q, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }

                if (listening) {
                    if (partial.isNotEmpty()) Text(partial, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                toast?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                if (typing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            input, { input = it }, Modifier.weight(1f), placeholder = { Text("Type to JARVIS") }, maxLines = 3,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton({ if (input.isNotBlank()) { vm.send(input); input = ""; typing = false } }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ typing = true }) { Icon(Icons.Filled.Keyboard, "Type", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton({ vm.toggleAssistMute() }) { Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) "Unmute microphone" else "Mute microphone", tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        FilledIconButton(
                            { vm.toggleMic() }, Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary),
                        ) { Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop" else "Speak", Modifier.size(30.dp)) }
                        if (speaking || busy) IconButton({ vm.stopAll() }) { Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }
}
