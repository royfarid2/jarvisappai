package com.jarvis.ai.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.ai.MainViewModel
import com.jarvis.ai.data.MemoryEntity

/** Memory manager: view, add, edit, delete, clear, export and import what JARVIS remembers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: MainViewModel, onBack: () -> Unit) {
    val mems by vm.memories.collectAsState()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    if (adding || editing != null) {
        var t by remember { mutableStateOf(editing?.text ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing != null) "Edit memory" else "Add memory") },
            text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6) },
            confirmButton = {
                TextButton({
                    editing?.let { vm.editMemory(it.id, t) } ?: vm.addMemory(t)
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ adding = false; editing = null }) { Text("Cancel") } },
        )
    }
    if (importing) {
        var j by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import memory") },
            text = { OutlinedTextField(j, { j = it }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10, placeholder = { Text("Paste exported JSON here") }) },
            confirmButton = {
                TextButton({
                    importing = false
                    vm.importMemories(j) { n -> note = if (n < 0) "That text is not a valid memory export." else "Imported $n new memories." }
                }) { Text("Import") }
            },
            dismissButton = { TextButton({ importing = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all memories?") },
        text = { Text("JARVIS will forget everything it has saved about you. This cannot be undone.") },
        confirmButton = { TextButton({ vm.clearMemories(); confirmClear = false }) { Text("Clear all") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton({
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.exportMemories()), "Export memory"))
                    }, enabled = mems.isNotEmpty()) { Icon(Icons.Filled.Share, "Export") }
                    IconButton({ importing = true }) { Icon(Icons.Filled.FileUpload, "Import") }
                    IconButton({ confirmClear = true }, enabled = mems.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, "Clear all") }
                },
            )
        },
        floatingActionButton = { FloatingActionButton({ adding = true }) { Icon(Icons.Filled.Add, "Add memory") } },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text(
                "Say \"my name is…\", \"remember that…\" or \"I prefer…\" in any chat and JARVIS saves it here. Say \"forget that…\" to remove it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cyan, modifier = Modifier.padding(bottom = 8.dp)) }
            if (mems.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Nothing saved yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(mems, key = { it.id }) { m ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cyan)
                                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton({ editing = m }) { Icon(Icons.Filled.Edit, "Edit", Modifier.size(20.dp)) }
                            IconButton({ vm.deleteMemory(m.id) }) { Icon(Icons.Filled.Delete, "Delete", Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}
