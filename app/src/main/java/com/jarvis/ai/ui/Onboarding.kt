package com.jarvis.ai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.ai.MainViewModel
import com.jarvis.ai.data.Settings

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf(Settings()) }
    AnimatedContent(
        targetState = step, label = "onboarding",
        transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) },
    ) { s ->
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (s == 0) {
                Spacer(Modifier.height(72.dp))
                JarvisLogo(logoSize = 168.dp, active = true)
                Spacer(Modifier.height(40.dp))
                Text("J.A.R.V.I.S.", style = MaterialTheme.typography.labelSmall, color = Cyan)
                Spacer(Modifier.height(12.dp))
                Text("Good evening.\nI am JARVIS.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Your personal assistant: conversation, voice, and control of your phone, with your permission, always.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(48.dp))
                Button({ step = 1 }, Modifier.fillMaxWidth().height(52.dp)) { Text("Get started") }
            } else {
                Spacer(Modifier.height(16.dp))
                JarvisLogo(logoSize = 64.dp)
                Spacer(Modifier.height(20.dp))
                Text("Connect my brain", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enter any OpenAI-compatible endpoint. Your key is stored encrypted on this device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                ConnectionFields(vm, draft) { draft = it }
                Spacer(Modifier.height(24.dp))
                Button(
                    { vm.saveSettings(draft.copy(onboarded = true)) },
                    Modifier.fillMaxWidth().height(52.dp), enabled = draft.endpoint.isNotBlank(),
                ) { Text("Continue") }
                TextButton({ vm.saveSettings(draft.copy(onboarded = true)) }) { Text("Skip for now") }
            }
        }
    }
}

/** Endpoint / key / model + Test Connection. Shared by onboarding and settings. */
@Composable
fun ConnectionFields(vm: MainViewModel, d: Settings, onChange: (Settings) -> Unit) {
    val test by vm.test.collectAsState()
    var showKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            d.endpoint, { onChange(d.copy(endpoint = it)) }, Modifier.fillMaxWidth(),
            label = { Text("API endpoint URL") }, placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            d.apiKey, { onChange(d.copy(apiKey = it)) }, Modifier.fillMaxWidth(),
            label = { Text("API key (if required)") }, singleLine = true,
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton({ showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
        )
        OutlinedTextField(
            d.model, { onChange(d.copy(model = it)) }, Modifier.fillMaxWidth(),
            label = { Text("Model") }, singleLine = true,
        )
        if (test.models.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(test.models.take(30).size) { i ->
                    val m = test.models[i]
                    FilterChip(d.model == m, { onChange(d.copy(model = m)) }, { Text(m) })
                }
            }
        }
        OutlinedButton({ vm.testConnection(d) }, Modifier.fillMaxWidth(), enabled = d.endpoint.isNotBlank() && !test.running) {
            if (test.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Test connection")
        }
        test.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (test.ok) Cyan else MaterialTheme.colorScheme.error)
        }
    }
}
