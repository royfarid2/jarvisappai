package com.jarvis.ai

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.actions.ActionRegistry
import com.jarvis.ai.actions.ActionResult
import com.jarvis.ai.data.MessageEntity
import com.jarvis.ai.data.Settings
import com.jarvis.ai.data.SettingsRepo
import com.jarvis.ai.net.ApiClient
import com.jarvis.ai.voice.SttManager
import com.jarvis.ai.voice.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx: Context = app.applicationContext
    private val repo = SettingsRepo(appCtx)
    private val dao = (app as JarvisApp).db.dao()
    private val api = ApiClient()

    val tts = TtsManager(appCtx) { s, t -> api.speech(s, t, appCtx.cacheDir) }
    val stt = SttManager(appCtx)

    val settings = MutableStateFlow<Settings?>(null)
    val messages: StateFlow<List<MessageEntity>> =
        dao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val streaming = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    val test = MutableStateFlow(TestUi())

    private var job: Job? = null
    private var voiceSession = false

    init {
        viewModelScope.launch {
            val s = repo.load()
            tts.settings = s
            settings.value = s
        }
        tts.onIdle = { resumeListeningIfNeeded() }
    }

    // ---------- settings ----------
    fun saveSettings(s: Settings) {
        viewModelScope.launch {
            repo.save(s)
            tts.settings = s
            settings.value = s
        }
    }

    fun previewVoice(draft: Settings) {
        tts.stop()
        tts.settings = draft
        tts.enqueue(if (draft.language.startsWith("ar")) "مرحباً، أنا جارفس. كيف أقدر أساعدك؟" else "Good evening, sir. All systems are online and ready.")
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
        tts.enqueue(if (s.language.startsWith("ar")) "مرحباً. جميع الأنظمة تعمل، كيف أقدر أخدمك؟" else "Good $part, sir. All systems are online. How may I assist you?")
    }

    fun clearHistory() { viewModelScope.launch { dao.clear() } }
    fun say(m: String) { toast.value = m }
    fun clearToast() { toast.value = null }

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
        if (stt.listening.value) { voiceSession = false; stt.stop(); return }
        tts.stop()                       // interrupt JARVIS if he is talking
        if (busy.value) job?.cancel()    // ... or still generating
        startListening(s)
    }

    private fun startListening(s: Settings) {
        voiceSession = true
        stt.start(
            s.language,
            onText = { text -> if (text.isBlank()) { voiceSession = false } else { send(text, true) } },
            onFail = { m -> voiceSession = false; if (m.isNotEmpty()) { toast.value = m } },
        )
    }

    private fun resumeListeningIfNeeded() {
        val s = settings.value ?: return
        if (s.handsFree && voiceSession && !busy.value && !tts.speaking.value && !stt.listening.value) startListening(s)
    }

    private suspend fun respond(text: String, s: Settings) {
        busy.value = true
        dao.insert(MessageEntity(role = "user", content = text))
        try {
            val match = ActionRegistry.find(text)
            if (match != null) {
                val res = try {
                    match.first.execute(appCtx, match.second)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { ActionResult("I wasn't able to do that: ${e.message}", false) }
                if (res.handled) {
                    dao.insert(MessageEntity(role = "assistant", content = res.message))
                    if (s.autoVoice) tts.enqueue(cleanForSpeech(res.message))
                    return
                }
            }
            chat(s)
        } finally {
            busy.value = false
            resumeListeningIfNeeded()
        }
    }

    private suspend fun chat(s: Settings) {
        if (s.endpoint.isBlank()) {
            dao.insert(MessageEntity(role = "note", content = "No AI endpoint is configured yet. Open Settings and add one so I can think, sir."))
            return
        }
        val history = dao.last(24).reversed().map { it.role to it.content }
        val msgs = listOf("system" to systemPrompt()) + history
        val sb = StringBuilder()
        var cursor = 0
        var failed: String? = null
        var completed = false
        streaming.value = ""
        try {
            api.chatStream(s, msgs).collect { chunk ->
                sb.append(chunk)
                streaming.value = sb.toString()
                if (s.autoVoice) cursor = flushSpeech(sb, cursor, false)
            }
            if (s.autoVoice) cursor = flushSpeech(sb, cursor, true)
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "Network error"
        } finally {
            withContext(NonCancellable) {
                streaming.value = null
                val full = sb.toString().trim()
                if (full.isNotEmpty()) dao.insert(MessageEntity(role = "assistant", content = full))
                if (failed != null) dao.insert(MessageEntity(role = "note", content = "I couldn't reach the AI service: $failed"))
            }
        }
        if (completed && !JarvisApp.foreground && sb.isNotBlank()) {
            Notifier.post(appCtx, "JARVIS", sb.toString().trim().take(140))
        }
    }

    /** Speaks completed sentences while the rest of the reply is still streaming. */
    private fun flushSpeech(sb: StringBuilder, cursor: Int, final: Boolean): Int {
        var end = -1
        for (i in cursor until sb.length) {
            val c = sb[i]
            val boundary = c == '\n' || c == '!' || c == '?' || c == '\u061F' ||
                (c == '.' && i + 1 < sb.length && sb[i + 1].isWhitespace())
            if (boundary) end = i + 1
        }
        if (final) end = sb.length
        if (end > cursor) {
            val chunk = sb.substring(cursor, end)
            if (final || chunk.length >= 24) {
                tts.enqueue(cleanForSpeech(chunk))
                return end
            }
        }
        return cursor
    }

    private fun cleanForSpeech(t: String): String = t
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_#`>~|]"), "")
        .replace(Regex("(?m)^\\s*[-\u2022]\\s+"), "")
        .trim()

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        return """You are J.A.R.V.I.S., a calm, dry-witted, extremely capable personal AI assistant living on the user's Android phone. Current date/time: $now.
Personality: poised, courteous, concise, quietly confident; occasionally address the user as "sir" in English.
Your replies are spoken aloud, so keep them short and natural. Avoid markdown, tables and emojis. Use plain sentences.
Reply in the language the user writes in. You understand Arabic including Iraqi and Saudi dialects; answer Arabic in simple, natural Arabic (match their dialect when sensible).
You can explain, summarise, search your knowledge, plan and help with tasks. The app itself handles device commands (open apps, YouTube/Google search, flashlight, alarms, battery, volume, settings, notifications, timed reminders) before messages reach you; if the user asks for something the phone cannot do, say so honestly.
Never claim to have done a device action yourself. Never invent facts; say when you are unsure."""
    }

    override fun onCleared() {
        tts.shutdown()
        stt.destroy()
    }
}
