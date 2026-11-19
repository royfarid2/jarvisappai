package com.jarvis.ai.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Offline "Hey Jarvis" detector: Sherpa-ONNX keyword spotter (3.3M-parameter streaming zipformer, int8).
 * Audio never leaves the phone and no account/key is needed. Keywords live in assets/kws/keywords.txt
 * (BPE tokens, generated with `sherpa-onnx-cli text2token`); per-keyword boost (:) and threshold (#) are tunable there.
 */
class SherpaWakeWord(private val ctx: Context, private val onWake: (keyword: String) -> Unit, private val onError: (String) -> Unit) {
    private var spotter: KeywordSpotter? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun load() {
        if (spotter != null) return
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "kws/encoder.int8.onnx", decoder = "kws/decoder.int8.onnx", joiner = "kws/joiner.int8.onnx",
            ),
            tokens = "kws/tokens.txt", numThreads = 1, provider = "cpu", modelType = "zipformer2",
        )
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = model, maxActivePaths = 4, keywordsFile = "kws/keywords.txt",
            keywordsScore = 1.0f, keywordsThreshold = 0.25f, numTrailingBlanks = 1,
        )
        spotter = KeywordSpotter(ctx.assets, cfg)
    }

    /** Starts listening. Releases the microphone again after the first detection (call start() to resume). */
    @Synchronized
    fun start() {
        if (running) return
        val sp = spotter ?: run { onError("Wake-word model is not loaded."); return }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError("Microphone format not supported."); return }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 2))
        } catch (e: SecurityException) { onError("Microphone permission missing."); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); onError("Could not open the microphone (another app may be using it)."); return
        }
        running = true
        thread = Thread({
            val stream = sp.createStream("")
            val shorts = ShortArray(SAMPLE_RATE / 10)           // 100 ms chunks
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(shorts, 0, shorts.size)
                    if (n < 0) { onError("Microphone read failed ($n)."); break }
                    if (n == 0) continue
                    val f = FloatArray(n) { shorts[it] / 32768f }
                    stream.acceptWaveform(f, SAMPLE_RATE)
                    while (sp.isReady(stream)) sp.decode(stream)
                    val kw = sp.getResult(stream).keyword
                    if (kw.isNotBlank()) {
                        sp.reset(stream)
                        running = false
                        onWake(kw)
                    }
                }
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
                try { stream.release() } catch (e: Exception) { }
                running = false
            }
        }, "jarvis-wakeword").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) { try { t.join(1000) } catch (e: InterruptedException) { } }
        thread = null
    }

    @Synchronized
    fun release() { stop(); try { spotter?.release() } catch (e: Exception) { }; spotter = null }

    private companion object { const val SAMPLE_RATE = 16000 }
}
