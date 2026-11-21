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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Speech output with text/voice sync.
 *  - Fish Audio cloud voice when a key is set (synthesis of the next sentence overlaps playback of the current one),
 *    otherwise / on failure the Android TextToSpeech engine (en-GB male preferred).
 *  - Each chunk can carry an [onStart] callback, fired on the main thread the moment that chunk begins to be heard,
 *    with its duration in ms. The UI uses it to reveal text exactly in step with the voice.
 */
class TtsManager(
    ctx: Context,
    private val cloudSynth: suspend (Settings, String) -> File?,
) {
    @Volatile var settings = Settings()
    var onIdle: (() -> Unit)? = null

    private class Chunk(val text: String, val onStart: ((Long) -> Unit)?)
    private class Ready(val chunk: Chunk, val file: File?)
    private class LocalCb(val len: Int, val cb: ((Long) -> Unit)?)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    val voices: StateFlow<List<Voice>> = _voices

    private val pending = AtomicInteger(0)
    private val synthQueue = Channel<Chunk>(Channel.UNLIMITED)
    private val readyQueue = Channel<Ready>(Channel.UNLIMITED)
    private val localCbs = ConcurrentHashMap<String, LocalCb>()
    private var synthJob: Job? = null
    private var playJob: Job? = null
    private var player: MediaPlayer? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private fun releaseEnhancer() { try { enhancer?.release() } catch (e: Exception) { }; enhancer = null }
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val c = localCbs.remove(utteranceId ?: return) ?: return
            val dur = (c.len * 62L / settings.rate.coerceAtLeast(0.5f)).toLong()
            main.post { c.cb?.invoke(dur) }
        }
        override fun onDone(utteranceId: String?) { finishOne() }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { utteranceId?.let { localCbs.remove(it) }; finishOne() }
        override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { localCbs.remove(it) }; finishOne() }
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
        startWorkers()
    }

    private fun finishOne() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            main.post { _speaking.value = false; onIdle?.invoke() }
        }
    }

    /** Queue [text] for speech. Blank text is a silent beat that still fires [onStart] in order. */
    fun enqueue(text: String, onStart: ((Long) -> Unit)? = null) {
        val t = text.trim()
        if (t.isEmpty() && onStart == null) return
        pending.incrementAndGet()
        _speaking.value = true
        if (settings.cloudTts && settings.fishKey.isNotBlank()) synthQueue.trySend(Chunk(t, onStart))
        else speakLocal(t, onStart)
    }

    fun stop() {
        pending.set(0)
        tts?.stop()
        localCbs.clear()
        synthJob?.cancel(); playJob?.cancel()
        while (synthQueue.tryReceive().isSuccess) { /* drain */ }
        while (true) { val r = readyQueue.tryReceive().getOrNull() ?: break; r.file?.delete() }
        try { player?.release() } catch (e: Exception) { }
        player = null
        releaseEnhancer()
        startWorkers()
        _speaking.value = false
    }

    fun shutdown() {
        stop(); synthJob?.cancel(); playJob?.cancel(); tts?.shutdown(); scope.cancel()
    }

    private fun startWorkers() {
        // Stage 1: synthesize ahead of playback.
        synthJob = scope.launch {
            for (c in synthQueue) {
                val f = if (c.text.isEmpty()) null else try { cloudSynth(settings, c.text) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { null }
                readyQueue.send(Ready(c, f))
            }
        }
        // Stage 2: play in order.
        playJob = scope.launch {
            for (r in readyQueue) {
                val c = r.chunk
                when {
                    c.text.isEmpty() -> { c.onStart?.invoke(0); finishOne() }
                    r.file != null -> {
                        try { play(r.file, c.onStart) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { c.onStart?.invoke(0) }
                        finally { r.file.delete() }
                        finishOne()
                    }
                    else -> speakLocal(c.text, c.onStart)   // graceful fallback to the on-device voice
                }
            }
        }
    }

    private suspend fun play(f: File, onStart: ((Long) -> Unit)?) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        Loudness.maxVolume(ctx.applicationContext)
        releaseEnhancer(); enhancer = Loudness.boost(mp)
        mp.setDataSource(f.absolutePath)
        mp.setOnPreparedListener { m -> onStart?.invoke(m.duration.toLong()); m.start() }
        mp.setOnCompletionListener { m ->
            m.release(); if (player === m) { player = null; releaseEnhancer() }
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { m, _, _ ->
            m.release(); if (player === m) { player = null; releaseEnhancer() }
            if (cont.isActive) cont.resume(Unit)
            true
        }
        cont.invokeOnCancellation { try { mp.release() } catch (e: Exception) { } }
        mp.prepareAsync()
    }

    private fun speakLocal(text: String, onStart: ((Long) -> Unit)?) {
        val t = tts
        if (!ready || t == null) { onStart?.invoke(0); finishOne(); return }
        val id = "j${System.nanoTime()}"
        localCbs[id] = LocalCb(text.length, onStart)
        if (text.isEmpty()) {
            if (t.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, id) == TextToSpeech.ERROR) { localCbs.remove(id); onStart?.invoke(0); finishOne() }
            return
        }
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
        if (t.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
            localCbs.remove(id); onStart?.invoke(0); finishOne()
        }
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
