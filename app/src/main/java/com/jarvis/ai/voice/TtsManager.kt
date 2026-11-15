package com.jarvis.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.ai.data.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Speech output. Two engines:
 *  1. Android TextToSpeech - picks a British (en-GB) male voice, low pitch by default.
 *  2. Optional cloud TTS via the configured OpenAI-compatible /audio/speech endpoint.
 * Chunks are queued so speech starts while the reply is still streaming.
 */
class TtsManager(
    ctx: Context,
    private val cloudSynth: suspend (Settings, String) -> File?,
) {
    @Volatile var settings = Settings()
    var onIdle: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    val voices: StateFlow<List<Voice>> = _voices

    private val pending = AtomicInteger(0)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var player: MediaPlayer? = null
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { finishOne() }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { finishOne() }
        override fun onError(utteranceId: String?, errorCode: Int) { finishOne() }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {}
    }

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(listener)
                try {
                    _voices.value = (tts?.voices ?: emptySet())
                        .filter { it.locale.language == "en" || it.locale.language == "ar" }
                        .sortedBy { it.name }
                } catch (e: Exception) { /* some engines throw here */ }
            }
        }
        startWorker()
    }

    private fun finishOne() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            main.post { _speaking.value = false; onIdle?.invoke() }
        }
    }

    fun enqueue(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        pending.incrementAndGet()
        _speaking.value = true
        if (settings.cloudTts && settings.endpoint.isNotBlank()) queue.trySend(t) else speakLocal(t)
    }

    fun stop() {
        pending.set(0)
        tts?.stop()
        worker?.cancel()
        while (queue.tryReceive().isSuccess) { /* drain */ }
        try { player?.release() } catch (e: Exception) { }
        player = null
        startWorker()
        _speaking.value = false
    }

    fun shutdown() {
        stop(); worker?.cancel(); tts?.shutdown(); scope.cancel()
    }

    private fun startWorker() {
        worker = scope.launch {
            for (t in queue) {
                val f = try { cloudSynth(settings, t) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { null }
                if (f != null) {
                    try { play(f) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { /* skip chunk */ }
                    finally { f.delete() }
                    finishOne()
                } else {
                    speakLocal(t)   // graceful fallback to on-device voice
                }
            }
        }
    }

    private suspend fun play(f: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        mp.setDataSource(f.absolutePath)
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { m ->
            m.release(); if (player === m) player = null
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { m, _, _ ->
            m.release(); if (player === m) player = null
            if (cont.isActive) cont.resume(Unit)
            true
        }
        cont.invokeOnCancellation { try { mp.release() } catch (e: Exception) { } }
        mp.prepareAsync()
    }

    private fun speakLocal(text: String) {
        val t = tts
        if (!ready || t == null) { finishOne(); return }
        val s = settings
        t.setSpeechRate(s.rate)
        t.setPitch(s.pitch)
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        if (arabic) {
            val loc = Locale("ar")
            if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.setLanguage(loc)
        } else {
            val v = pickVoice(t, s)
            if (v != null) t.setVoice(v) else t.setLanguage(Locale.UK)
        }
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, "j${System.nanoTime()}")
        if (r == TextToSpeech.ERROR) finishOne()
    }

    private fun pickVoice(t: TextToSpeech, s: Settings): Voice? {
        val all = try { t.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        all.firstOrNull { it.name == s.voiceName }?.let { return it }
        val gb = all.filter { it.locale.language == "en" && it.locale.country == "GB" }
        // Google en-GB male voice codes: gbd, rjs, gbb
        for (k in listOf("gbd", "rjs", "gbb", "male")) {
            gb.firstOrNull { it.name.contains(k, true) && !it.name.contains("female", true) }?.let { return it }
        }
        return gb.firstOrNull()
    }
}
