package com.jarvis.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.MainViewModel

/** Manual terminal for the sandboxed workspace: real commands, live output, history, file list, ZIP export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel, onBack: () -> Unit) {
    val log by vm.terminalLog.collectAsState()
    val live by vm.terminalLive.collectAsState()
    val running by vm.terminalRunning.collectAsState()
    var cmd by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val mono = FontFamily.Monospace
    val state = rememberLazyListState()
    LaunchedEffect(log.size, live) { val n = log.size + (if (live.isNotEmpty()) 1 else 0); if (n > 0) state.scrollToItem(n - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton({ vm.runCommand("ls -la") }) { Icon(Icons.Filled.Folder, "List files") }
                    IconButton({ vm.exportWorkspaceZip { note = it } }) { Icon(Icons.Filled.FolderZip, "Export workspace as ZIP") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Text(
                "Sandboxed workspace (toybox shell: ls cat mkdir cp mv rm grep sed awk find tar). No Python, Node or Git here. Paths outside the workspace are refused.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cyan, modifier = Modifier.padding(bottom = 6.dp)) }
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF07080A)) {
                LazyColumn(Modifier.padding(12.dp), state = state) {
                    items(log) { e ->
                        Text("$ ${e.command}", fontFamily = mono, fontSize = 12.sp, color = Cyan)
                        if (e.output.isNotBlank()) Text(e.output, fontFamily = mono, fontSize = 12.sp, color = Color(0xFFD7DAE0))
                        Text("exit ${e.exit} · ${e.ms} ms", fontFamily = mono, fontSize = 10.sp, color = if (e.exit == 0) Color(0xFF7A828E) else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (live.isNotEmpty()) item { Text(live, fontFamily = mono, fontSize = 12.sp, color = Color(0xFFD7DAE0)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("command") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono),
                )
                Spacer(Modifier.width(8.dp))
                if (running) FilledIconButton({ vm.cancelCommand() }) { Icon(Icons.Filled.Stop, "Cancel") }
                else FilledIconButton({ vm.runCommand(cmd); cmd = "" }, enabled = cmd.isNotBlank()) { Icon(Icons.Filled.PlayArrow, "Run") }
            }
        }
    }
}
