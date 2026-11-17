package com.jarvis.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val LINES = listOf(
    "INITIALIZING J.A.R.V.I.S.",
    "LOADING LANGUAGE CORE .......... OK",
    "VOICE SYNTHESIS ................ OK",
    "DEVICE INTERFACE ............... OK",
    "ALL SYSTEMS ONLINE",
)

/** Short cinematic start-up sequence. Calls [onDone] when finished. */
@Composable
fun BootScreen(onDone: () -> Unit) {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in 1..LINES.size) { delay(420); shown = i }
        delay(500)
        onDone()
    }
    Box(Modifier.fillMaxSize()) {
        HudBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            JarvisLogo(logoSize = 150.dp, active = true)
            Spacer(Modifier.height(36.dp))
            Column(Modifier.fillMaxWidth()) {
                LINES.take(shown).forEachIndexed { i, l ->
                    Text(
                        "> $l", style = MaterialTheme.typography.labelMedium,
                        color = if (i == LINES.lastIndex) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
