package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Speech-to-text using Android's SpeechRecognizer. Must be used from the main thread. */
class SttManager(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    fun start(lang: String, onText: (String) -> Unit, onFail: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onFail("Speech recognition isn't available on this phone."); return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish(); onText(text)
            }
            override fun onError(error: Int) {
                finish()
                onFail(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
                    else -> "Couldn't hear you (error $error)."
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        _partial.value = ""
        _listening.value = true
        r.startListening(i)
    }

    private fun finish() {
        _listening.value = false; _level.value = 0f; _partial.value = ""
        rec?.destroy(); rec = null
    }

    fun stop() { if (rec != null) finish() }
    fun destroy() = stop()
}
