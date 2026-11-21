package com.jarvis.ai.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer

/**
 * Makes JARVIS very loud: puts the media volume at maximum and adds a software gain (LoudnessEnhancer) on top of it.
 * Android cannot go beyond the phone's maximum without this gain; at very high gain some voices may distort slightly.
 */
object Loudness {
    const val GAIN_MB = 1800          // +18 dB; lower it if the voice sounds distorted

    fun maxVolume(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (e: Exception) { }
    }

    /** Returns the enhancer so the caller can release it together with the player, or null if the device lacks it. */
    fun boost(mp: MediaPlayer): LoudnessEnhancer? = try {
        mp.setVolume(1f, 1f)
        LoudnessEnhancer(mp.audioSessionId).apply { setTargetGain(GAIN_MB); enabled = true }
    } catch (e: Exception) { null }
}
