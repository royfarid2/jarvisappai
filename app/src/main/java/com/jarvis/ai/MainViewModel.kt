package com.jarvis.ai

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.actions.ActionRegistry
import com.jarvis.ai.actions.ActionResult
import com.jarvis.ai.assist.AssistLauncher
import com.jarvis.ai.assist.HotwordService
import com.jarvis.ai.agent.AgentControl
import com.jarvis.ai.agent.AgentRunner
import com.jarvis.ai.agent.JarvisAccessibilityService
import com.jarvis.ai.data.MessageEntity
import com.jarvis.ai.data.SessionEntity
import com.jarvis.ai.data.Settings
import com.jarvis.ai.data.SettingsRepo
import com.jarvis.ai.net.ApiClient
import com.jarvis.ai.net.ChatMsg
import com.jarvis.ai.server.FreeLlmServer
import com.jarvis.ai.server.ServerStatus
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import com.jarvis.ai.voice.SttManager
import com.jarvis.ai.voice.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TestUi(
    val running: Boolean = false,
    val message: String? = null,
    val ok: Boolean = false,
    val models: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx: Context = app.applicationContext
    private val repo = SettingsRepo(appCtx)
    private val dao = (app as JarvisApp).db.dao()
    private val api = ApiClient()
    private val statePrefs = appCtx.getSharedPreferences("jarvis_state", Context.MODE_PRIVATE)

    val tts = TtsManager(appCtx) { s, t -> api.speech(s, t, appCtx.cacheDir) }
    val stt = SttManager(appCtx)
    private val agent = AgentRunner(appCtx, api) { tts.enqueue(it) }

    val settings = MutableStateFlow<Settings?>(null)

    // ----- sessions -----
    val sessions: StateFlow<List<SessionEntity>> = dao.sessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val currentSession = MutableStateFlow(statePrefs.getLong("session", -1L))
    val messages: StateFlow<List<MessageEntity>> = currentSession
        .flatMapLatest { if (it < 0) flowOf(emptyList()) else dao.observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val streaming = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    val test = MutableStateFlow(TestUi())
    val agentMode = MutableStateFlow(false)       // true = every message is an agent task; false = auto-route
    val agentRunning = MutableStateFlow(false)
    private var assistOpen = false
    val assistMuted = MutableStateFlow(false)
    private var savedSession: Long? = null     // the chat the main app had open, restored when the panel closes
    private var idleRetries = 0
    private var originAssist = false

    private var job: Job? = null
    private var voiceSession = false

    init {
        viewModelScope.launch {
            val s = repo.load()
            tts.settings = s
            settings.value = s
            startServerMonitor()
        }
        tts.onIdle = { resumeListeningIfNeeded() }
    }

    // ---------- FreeLLMAPI connection: health monitor, reconnect, Start / Stop / Restart ----------
    val serverStatus: StateFlow<ServerStatus> = FreeLlmServer.status
    private var monitorJob: Job? = null

    /**
     * Polls <server>/api/ping. It only runs while the app is visible (or auto-start is on) so an idle phone is not
     * woken for nothing. After two failed probes it asks Termux to start the server (rate-limited, never a duplicate:
     * the control script refuses to start a second instance).
     */
    private fun startServerMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = viewModelScope.launch {
            while (true) {
                val s = settings.value
                if (s != null && s.endpoint.isNotBlank() && (JarvisApp.foreground || s.autoStartServer)) {
                    val was = FreeLlmServer.status.value
                    val ok = FreeLlmServer.check(api, s)
                    if (ok && was is ServerStatus.Offline) toast.value = "AI server reconnected."
                    if (!ok && FreeLlmServer.consecutiveFailures() >= 2) FreeLlmServer.maybeRecover(appCtx, s)
                }
                delay(if (FreeLlmServer.status.value is ServerStatus.Online) 20_000 else 8_000)
            }
        }
    }

    fun refreshServer(draft: Settings) { viewModelScope.launch { FreeLlmServer.check(api, draft) } }

    fun serverCommand(action: String) {
        toast.value = FreeLlmServer.command(appCtx, action)
        viewModelScope.launch {
            delay(4000)
            settings.value?.let { FreeLlmServer.check(api, it) }
        }
    }

    // ---------- settings ----------
    fun saveSettings(s: Settings) {
        viewModelScope.launch {
            repo.save(s)
            tts.settings = s
            settings.value = s
            if (s.hotword) HotwordService.start(appCtx) else HotwordService.stop(appCtx)
        }
    }

    fun previewVoice(draft: Settings) {
        tts.stop()
        tts.settings = draft
        tts.enqueue("Good evening, sir. All systems are online and ready.")
    }

    fun testConnection(draft: Settings) {
        viewModelScope.launch {
            test.value = TestUi(running = true)
            val r = api.test(draft)
            test.value = r.fold(
                onSuccess = { TestUi(message = if (it.isEmpty()) "Connected. Endpoint answered." else "Connected. ${it.size} models available.", ok = true, models = it) },
                onFailure = { TestUi(message = "Failed: ${it.message ?: it.javaClass.simpleName}") },
            )
        }
    }

    private var greeted = false
    /** Spoken once per launch, like the film: time-aware greeting. */
    fun greet() {
        val s = settings.value ?: return
        if (greeted || !s.autoVoice) return
        greeted = true
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when { h < 5 -> "evening"; h < 12 -> "morning"; h < 18 -> "afternoon"; else -> "evening" }
        tts.enqueue("Good $part, sir. All systems are online. How may I assist you?")
    }

    /** Hey-JARVIS panel opened: fresh chat, start listening at once. */
    // ----- wake-word greeting: generated text, spoken live by the same TTS voice as every other reply -----
    /** True from wake activation until the spoken greeting has finished (the panel shows "Speaking"). */
    val introPlaying = MutableStateFlow(false)
    private var greetJob: Job? = null
    private var lastWakeAt = 0L

    private fun stopIntro() {
        greetJob?.cancel(); greetJob = null
        if (introPlaying.value) tts.stop()
        introPlaying.value = false
    }

    private fun timeOfDay(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when { h < 5 -> "Good evening"; h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    }

    /** Fills the template placeholders: {greeting} (time-aware), {time}, {date}. */
    fun renderGreeting(template: String): String {
        val now = Date()
        return template.ifBlank { com.jarvis.ai.data.DEFAULT_GREETING }
            .replace("{greeting}", timeOfDay())
            .replace("{time}", SimpleDateFormat("h:mm a", Locale.UK).format(now))
            .replace("{date}", SimpleDateFormat("EEEE d MMMM", Locale.UK).format(now))
    }

    /** Template greeting, or one written by the model when enabled (2.5 s budget, then the template). */
    private suspend fun buildGreeting(s: Settings): String {
        val fallback = renderGreeting(s.greeting)
        if (!s.aiGreeting || s.endpoint.isBlank()) return fallback
        val line = withTimeoutOrNull(2500) {
            try {
                api.chatOnce(s, listOf(
                    ChatMsg("system", "You are JARVIS, a composed, dry-witted British AI butler. Reply with ONE short spoken greeting (max 14 words) that confirms you are online and ready. Address the user as \"sir\". No emojis, no quotes."),
                    ChatMsg("user", "It is ${timeOfDay().removePrefix("Good ").lowercase()}, ${SimpleDateFormat("h:mm a", Locale.UK).format(Date())}. The user just said your wake word."),
                ), 60).trim().trim('"').takeIf { it.isNotBlank() && it.length <= 160 }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        return line ?: fallback
    }

    /**
     * Wake activation: speak the greeting through [tts] (Fish Audio JARVIS voice, or the on-device voice if that is
     * unavailable), then start listening. Tapping the mic during the greeting skips it.
     */
    private fun greetThenListen(s: Settings) {
        stopIntro()
        introPlaying.value = true
        voiceSession = false                       // so tts.onIdle does not start a second listener; we do it below
        greetJob = viewModelScope.launch {
            try {
                val text = buildGreeting(s)
                if (!assistOpen) return@launch
                tts.enqueue(cleanForSpeech(text))
                withTimeoutOrNull(20_000) { tts.speaking.first { !it } }
            } finally {
                withContext(NonCancellable) { introPlaying.value = false }
            }
            if (assistOpen && !assistMuted.value && !busy.value && !stt.listening.value) startListening(s)
        }
    }

    /** Settings-screen "Test greeting": speaks the greeting exactly as a wake activation would. */
    fun previewGreeting(draft: Settings) {
        viewModelScope.launch {
            tts.stop(); tts.settings = draft
            tts.enqueue(cleanForSpeech(buildGreeting(draft)))
        }
    }

    fun assistStart(fromWake: Boolean = false) {
        val s = settings.value ?: return
        // The same wake event can arrive twice (detector + notification, onNewIntent...). One greeting only.
        val now = System.currentTimeMillis()
        if (fromWake && (introPlaying.value || now - lastWakeAt < 4000)) return
        if (fromWake) lastWakeAt = now
        assistOpen = true
        assistMuted.value = false
        idleRetries = 0
        if (busy.value) return
        tts.stop()
        if (savedSession == null) savedSession = currentSession.value
        newChat()                                   // the panel gets its own session; the main app's chat is restored afterwards
        if (fromWake) greetThenListen(s) else startListening(s)
    }

    /** Panel closed: stop listening/speaking unless an agent task is carrying on in the background. */
    fun assistEnd() {
        assistOpen = false
        stopIntro()
        if (!agentRunning.value) {
            stt.stop(); tts.stop(); voiceSession = false
            savedSession?.let { openSession(it) }
            savedSession = null
        }
    }

    fun say(m: String) { toast.value = m }
    fun clearToast() { toast.value = null }

    // ---------- sessions ----------
    fun openSession(id: Long) {
        if (busy.value) return
        currentSession.value = id
        statePrefs.edit().putLong("session", id).apply()
    }
    fun newChat() = openSession(-1L)
    fun renameSession(id: Long, title: String) { viewModelScope.launch { if (title.isNotBlank()) dao.rename(id, title.trim().take(60)) } }
    fun pinSession(id: Long, pinned: Boolean) { viewModelScope.launch { dao.setPinned(id, pinned) } }
    fun archiveSession(id: Long, archived: Boolean) {
        viewModelScope.launch { dao.setArchived(id, archived) }
        if (archived && currentSession.value == id) newChat()
    }
    suspend fun searchSessionIds(q: String): List<Long> = dao.searchSessions(q)
    fun deleteSession(id: Long) {
        viewModelScope.launch {
            dao.deleteMessages(id); dao.deleteSession(id)
            if (currentSession.value == id) openSession(-1L)
        }
    }
    fun clearHistory() { viewModelScope.launch { dao.clearMessages(); dao.clearSessions(); openSession(-1L) } }

    private suspend fun ensureSession(firstMessage: String): Pair<Long, Boolean> {
        val cur = currentSession.value
        if (cur >= 0) return cur to false
        val id = dao.insertSession(SessionEntity(title = firstMessage.replace('\n', ' ').take(40)))
        currentSession.value = id
        statePrefs.edit().putLong("session", id).apply()
        return id to true
    }

    // ---------- conversation ----------
    fun send(text: String, fromVoice: Boolean = false) {
        val t = text.trim()
        val s = settings.value
        if (t.isEmpty() || s == null || busy.value) return
        voiceSession = fromVoice
        tts.stop()
        job = viewModelScope.launch { respond(t, s) }
    }

    fun stopAll() {
        voiceSession = false
        AgentControl.stop = true
        job?.cancel()
        tts.stop()
        stt.stop()
    }

    fun replay(text: String) {
        tts.stop()
        text.split(Regex("(?<=[.!?\u061F\n])\\s+")).forEach { tts.enqueue(cleanForSpeech(it)) }
    }

    fun toggleMic() {
        val s = settings.value ?: return
        if (introPlaying.value) { stopIntro(); assistMuted.value = false; startListening(s); return }   // skip the intro
        if (stt.listening.value) { voiceSession = false; stt.stop(); return }
        tts.stop()                       // interrupt JARVIS if he is talking
        assistMuted.value = false
        if (busy.value) stopAll()        // ... or still generating
        startListening(s)
    }

    private fun startListening(s: Settings) {
        voiceSession = true
        stt.start(
            s.language,
            onText = { text ->
                if (text.isBlank()) retryListening(s) else { idleRetries = 0; send(text, true) }
            },
            onFail = { m ->
                if (m.isEmpty()) retryListening(s)
                else { voiceSession = false; toast.value = m }
            },
        )
    }

    /** In the Hey-JARVIS panel silence is not the end of the conversation: listen again a few times before waiting for the mic button. */
    private fun retryListening(s: Settings) {
        if (assistOpen && !assistMuted.value && idleRetries < 4) {
            idleRetries++
            viewModelScope.launch { delay(300); if (assistOpen && !assistMuted.value && !busy.value && !tts.speaking.value && !stt.listening.value) startListening(s) }
        } else voiceSession = false
    }

    fun toggleAssistMute() {
        val s = settings.value ?: return
        if (assistMuted.value) { assistMuted.value = false; idleRetries = 0; if (!busy.value && !tts.speaking.value) startListening(s) }
        else { assistMuted.value = true; voiceSession = false; stt.stop() }
    }

    private fun resumeListeningIfNeeded() {
        val s = settings.value ?: return
        if (assistMuted.value) return
        if ((s.handsFree || assistOpen) && voiceSession && !busy.value && !tts.speaking.value && !stt.listening.value) startListening(s)
    }

    private suspend fun respond(text: String, s: Settings) {
        busy.value = true
        val (sid, isNew) = ensureSession(text)
        dao.insert(MessageEntity(sessionId = sid, role = "user", content = text))
        dao.touch(sid, System.currentTimeMillis())
        try {
            originAssist = assistOpen
            if (agentMode.value) { runAgent(text, s, sid); return }
            if (SCREEN_Q.containsMatchIn(ActionRegistry.normalize(text))) { chat(s, sid, screen = true); return }
            val match = ActionRegistry.find(text)
            if (match != null) {
                val res = try {
                    match.first.execute(appCtx, match.second)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { ActionResult("I wasn't able to do that: ${e.message}", false) }
                if (res.handled) {
                    dao.insert(MessageEntity(sessionId = sid, role = "assistant", content = res.message))
                    if (s.autoVoice) tts.enqueue(cleanForSpeech(res.message))
                    return
                }
            }
            if (needsDeviceControl(text) && JarvisAccessibilityService.isEnabled(appCtx)) { runAgent(text, s, sid); return }
            chat(s, sid, screen = false)
            if (isNew) titleFor(s, sid)
        } finally {
            busy.value = false
            resumeListeningIfNeeded()
        }
    }

    // ----- text revealed in step with the voice -----
    private val visible = StringBuilder()
    private var revealJob: Job? = null
    private var revealTail = ""

    private fun resetReveal() { revealJob?.cancel(); revealTail = ""; visible.setLength(0) }

    /** Called when a chunk starts being heard: types [display] out over the chunk's audio duration. */
    private fun reveal(display: String, durMs: Long) {
        revealJob?.cancel()
        if (revealTail.isNotEmpty()) { visible.append(revealTail); revealTail = "" }
        if (durMs < 300 || display.length < 4) {
            visible.append(display); streaming.value = visible.toString(); return
        }
        revealTail = display
        revealJob = viewModelScope.launch {
            val steps = (durMs * 0.9 / 33).toInt().coerceAtLeast(1)
            val total = display.length
            val base = visible.toString()
            for (i in 1..steps) {
                streaming.value = base + display.substring(0, total * i / steps)
                delay(33)
            }
            visible.append(display); revealTail = ""
            streaming.value = visible.toString()
        }
    }

    private suspend fun chat(s: Settings, sid: Long, screen: Boolean) {
        if (s.endpoint.isBlank()) {
            dao.insert(MessageEntity(sessionId = sid, role = "note", content = "No AI endpoint is configured yet. Open Settings and add one so I can think, sir."))
            return
        }
        var history = dao.last(sid, 24).reversed().map { ChatMsg(it.role, it.content) }
        var useS = s
        if (screen) {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                dao.insert(MessageEntity(sessionId = sid, role = "note", content = "To look at your screen I need the JARVIS accessibility service. Enable it in Settings > Permissions, then ask again."))
                return
            }
            if (JarvisApp.foreground) {
                val warn = "Switch to the screen you want me to look at. I'll look in six seconds."
                dao.insert(MessageEntity(sessionId = sid, role = "assistant", content = warn))
                if (s.autoVoice) tts.enqueue(warn)
                delay(6000)
            }
            val snap = svc.snapshot()
            val shot = if (s.agentVision) svc.screenshotB64() else null
            val last = history.last()
            history = history.dropLast(1) + ChatMsg("user", last.content + "\n\n[SCREEN CONTENT of app ${snap.pkg}]\n${snap.text}", shot)
            if (shot != null) useS = s.copy(model = s.visionModel)
        }
        val msgs = listOf(ChatMsg("system", systemPrompt())) + history
        val sb = StringBuilder()
        var cursor = 0
        var failed: String? = null
        var completed = false
        resetReveal()
        streaming.value = ""
        try {
            var attempt = 0
            while (true) {
                try {
                    api.chatStream(useS, msgs).collect { chunk ->
                        sb.append(chunk)
                        if (s.autoVoice) cursor = flushSpeech(sb, cursor, false) else streaming.value = sb.toString()
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (sb.isNotEmpty()) throw e                        // part of the answer was already spoken: never repeat it
                    val msg = e.message.orEmpty()
                    val viaRouter = FreeLlmServer.isLocalEndpoint(useS) || useS.endpoint.contains(":3001")
                    if (viaRouter && useS.model != "auto" && (msg.startsWith("HTTP 404") || msg.contains("model_not_found"))) {
                        useS = useS.copy(model = "auto"); continue      // chosen model vanished: let FreeLLMAPI's router pick
                    }
                    val transient = (!msg.startsWith("HTTP 4") || msg.startsWith("HTTP 429")) && !msg.contains("no_providers_configured")
                    if (transient && attempt < 2) { attempt++; delay(1200L * attempt); continue }   // brief outage: reconnect
                    throw e
                }
            }
            if (s.autoVoice) {
                cursor = flushSpeech(sb, cursor, true)
                tts.speaking.first { !it }          // let the voice (and the text reveal) finish
            }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "Network error"
        } finally {
            withContext(NonCancellable) {
                revealJob?.cancel()
                streaming.value = null
                val full = sb.toString().trim()
                if (full.isNotEmpty()) dao.insert(MessageEntity(sessionId = sid, role = "assistant", content = full))
                if (failed != null) dao.insert(MessageEntity(sessionId = sid, role = "note", content = "I couldn't reach the AI service: $failed"))
            }
        }
        if (completed && !JarvisApp.foreground && sb.isNotBlank()) {
            Notifier.post(appCtx, "JARVIS", cleanForSpeech(sb.toString()).take(140))
        }
    }

    /** Sends finished sentences / code blocks to the voice; their text appears when they are heard. */
    private fun flushSpeech(sb: StringBuilder, cursor: Int, final: Boolean): Int {
        var end = -1
        var inFence = false
        var i = cursor
        while (i < sb.length) {
            if (sb.startsWith("```", i)) {
                inFence = !inFence
                i += 3
                if (!inFence) end = i              // a closed code block is a chunk boundary
                continue
            }
            val c = sb[i]
            if (!inFence && (c == '\n' || c == '!' || c == '?' || c == '\u061F' ||
                    (c == '.' && i + 1 < sb.length && sb[i + 1].isWhitespace()))) end = i + 1
            // first audio sooner: the very first chunk of a reply may end at a clause break once it is long enough
            else if (!inFence && cursor == 0 && end < 0 && (c == ',' || c == ';' || c == ':') &&
                i + 1 < sb.length && sb[i + 1] == ' ' && i + 1 >= 40) end = i + 1
            i++
        }
        if (final) end = sb.length
        if (end > cursor) {
            val display = sb.substring(cursor, end)
            if (final || display.length >= (if (cursor == 0) 16 else 24) || display.contains("```")) {
                tts.enqueue(cleanForSpeech(display)) { dur -> reveal(display, dur) }
                return end
            }
        }
        return cursor
    }

    private fun cleanForSpeech(t: String): String = t
        .replace(Regex("```[\\s\\S]*?(```|$)"), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_#`>~|]"), "")
        .replace(Regex("(?m)^\\s*[-\u2022]\\s+"), "")
        .trim()

    private suspend fun titleFor(s: Settings, sid: Long) {
        try {
            val first = dao.last(sid, 2).reversed().joinToString("\n") { it.role + ": " + it.content.take(300) }
            val t = api.chatOnce(s, listOf(
                ChatMsg("system", "Write a 2-5 word title for this conversation, in the conversation's language. Reply with the title only, no quotes."),
                ChatMsg("user", first)), 60).trim().trim('"', '\'', '.').take(48)
            if (t.isNotBlank()) dao.rename(sid, t)
        } catch (e: Exception) { /* keep the truncated first message as title */ }
    }

    // ---------- agent mode ----------
    private suspend fun runAgent(task: String, s: Settings, sid: Long) {
        if (!JarvisAccessibilityService.isEnabled(appCtx)) {
            dao.insert(MessageEntity(sessionId = sid, role = "note", content = "Agent mode needs the JARVIS accessibility service. Enable it in Settings > Permissions and try again."))
            return
        }
        AgentControl.stop = false
        agentRunning.value = true
        val steps = ArrayList<String>()
        streaming.value = "```\nStarting...\n```"
        Notifier.agentStatus(appCtx, task.take(80))
        val result = try {
            agent.run(task, s) { line ->
                steps.add(line)
                streaming.value = "```\n" + steps.joinToString("\n") + "\n```"
                Notifier.agentStatus(appCtx, line)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "The agent stopped unexpectedly: ${e.message}"
        } finally {
            Notifier.cancel(appCtx, Notifier.ID_AGENT)
            AgentControl.stop = false
            agentRunning.value = false
            withContext(NonCancellable) { streaming.value = null }
        }
        val text = result + if (steps.isNotEmpty()) "\n\n```steps\n" + steps.joinToString("\n") + "\n```" else ""
        dao.insert(MessageEntity(sessionId = sid, role = "assistant", content = text))
        // report back: the Hey-JARVIS panel if that is where the request came from, otherwise the app
        if (s.autoVoice) tts.enqueue(cleanForSpeech(result))
        if (originAssist) AssistLauncher.show(appCtx, resultOnly = true)
        else try {
            appCtx.startActivity(Intent(appCtx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) { /* background start blocked: the notification below still reports */ }
        if (!JarvisApp.foreground) Notifier.post(appCtx, "JARVIS", result.take(140))
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        return """You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), the personal AI assistant created by Tony Stark, living on the user's Android phone. Stay in this character naturally and never comment on playing a role. Current date/time: $now.
Personality: extremely intelligent, calm, sophisticated and professional; dry, understated wit and the occasional light sarcasm when it suits the moment; confident without arrogance; articulate and conversational, never a customer-support bot.
ANSWER FIRST. The default is to answer or report the result directly, in the first sentence. Do not open with an acknowledgement. Occasionally, and only when it is natural, you may use a brief phrase such as "Certainly", "Of course", "Understood" or "Right away"; never start every reply with one, never repeat the same phrase two replies in a row, never say "On it, sir", and use "sir" sparingly (at most now and then, never in every sentence). An acknowledgement is never a substitute for the answer.
Context: you remember the whole conversation. Short follow-ups ("how many members?", "and tomorrow?", "play it") refer to what was just discussed; resolve them yourself and ask a clarifying question only when the request is truly ambiguous. Match the length to the question: one or two sentences for simple things, more only when the subject needs it. Explain things directly and engagingly, with no filler introductions.
Your replies are spoken aloud while they appear, so write natural spoken sentences. Then, when helpful, add structure the screen can show:
- ALWAYS put code, commands, config or file contents in a fenced block with a language tag. Code is shown in a copyable box and never read aloud, so introduce it in one sentence first.
- Use short lists, **bold** and ## headings only when they genuinely help. No emojis.
LANGUAGE: ALWAYS reply in English only, whatever language the user writes or speaks. You understand Arabic perfectly, including Iraqi and Saudi dialects, but every reply is in English.
Capabilities: you can explain, plan, write and debug code, summarise and help with tasks. The app itself handles device commands (open apps, YouTube/Google search, flashlight, alarms, battery, volume, settings, notifications, reminders). You can operate the phone for the user (open apps, play songs, send messages, read information inside apps such as Discord); such requests are carried out automatically once the JARVIS accessibility service is enabled. If it isn't, tell the user to enable it in Settings > Permissions. For "what's on my screen", the user can simply ask.
Honesty: never claim a device action is done before it has actually succeeded, never invent numbers or facts, and say plainly when you are unsure or when something failed."""
    }

    override fun onCleared() {
        tts.shutdown()
        stt.destroy()
    }

    private val appLabels: List<String> by lazy {
        try {
            val pm = appCtx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString().lowercase() }.filter { it.length >= 3 }.distinct()
        } catch (e: Exception) { emptyList() }
    }

    /** Does this request mean "operate the phone / apps" rather than "chat"? */
    private fun needsDeviceControl(text: String): Boolean {
        val t = ActionRegistry.normalize(text)
        if (DEVICE_VERBS.containsMatchIn(t)) return true
        return appLabels.any { l ->
            Regex("\\b(?:on|in|from|using|via|inside|within)\\s+(?:my\\s+|the\\s+)?" + Regex.escape(l) + "\\b").containsMatchIn(t)
        }
    }

    private companion object {
        val DEVICE_VERBS = Regex(
            "^(?:play|pause|resume|skip|launch|go to|navigate to|message|text|dm|call|ring|send|reply|post|tweet|follow|subscribe|" +
                "take a screenshot|scroll|tap|click|join|mute|unmute|check my|read my|show me my|" +
                "how many (?:members|followers|subscribers|friends|messages|likes|views|unread)|who (?:messaged|texted|called))\\b"
        )
        val SCREEN_Q = Regex(
            "what(?:'s| is) on (?:my |the )?screen|read (?:my |the )?screen|describe (?:my |the )?screen|what am i looking at|summari[sz]e (?:this|my) screen|" +
                "(?:وش|شنو|ايش|ماذا|ويش) (?:على|في|ب) ?(?:ال)?(?:شاشه|شاشتي)|اقرا (?:لي )?(?:ال)?شاشه|وصف (?:لي )?(?:ال)?شاشه"
        )
    }
}
