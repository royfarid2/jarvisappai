package com.jarvis.ai.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** API key lives in EncryptedSharedPreferences (AES-256, master key in Android Keystore). */
class SecureStore(private val ctx: Context) {
    private val prefs by lazy {
        fun create() = EncryptedSharedPreferences.create(
            ctx, FILE,
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        try { create() } catch (e: Exception) {
            ctx.deleteSharedPreferences(FILE)   // keystore got reset: start clean
            create()
        }
    }
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) { prefs.edit().putString("api_key", v).apply() }

    private companion object { const val FILE = "jarvis_secure" }
}

class SettingsRepo(private val ctx: Context) {
    private val secure by lazy { SecureStore(ctx) }

    suspend fun load(): Settings = withContext(Dispatchers.IO) {
        val p = ctx.dataStore.data.first()
        val d = Settings()
        Settings(
            endpoint = p[ENDPOINT] ?: d.endpoint,
            apiKey = secure.apiKey,
            model = p[MODEL] ?: d.model,
            voiceName = p[VOICE] ?: d.voiceName,
            rate = p[RATE] ?: d.rate,
            pitch = p[PITCH] ?: d.pitch,
            language = p[LANG] ?: d.language,
            dark = p[DARK] ?: d.dark,
            autoVoice = p[AUTO_VOICE] ?: d.autoVoice,
            handsFree = p[HANDS_FREE] ?: d.handsFree,
            cloudTts = p[CLOUD] ?: d.cloudTts,
            cloudTtsModel = p[CLOUD_MODEL] ?: d.cloudTtsModel,
            cloudVoice = p[CLOUD_VOICE] ?: d.cloudVoice,
            onboarded = p[ONBOARDED] ?: d.onboarded,
        )
    }

    suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        secure.apiKey = s.apiKey.trim()
        ctx.dataStore.edit { p ->
            p[ENDPOINT] = s.endpoint.trim(); p[MODEL] = s.model.trim(); p[VOICE] = s.voiceName
            p[RATE] = s.rate; p[PITCH] = s.pitch; p[LANG] = s.language; p[DARK] = s.dark
            p[AUTO_VOICE] = s.autoVoice; p[HANDS_FREE] = s.handsFree; p[CLOUD] = s.cloudTts
            p[CLOUD_MODEL] = s.cloudTtsModel.trim(); p[CLOUD_VOICE] = s.cloudVoice.trim()
            p[ONBOARDED] = s.onboarded
        }
    }

    private companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val VOICE = stringPreferencesKey("voice")
        val RATE = floatPreferencesKey("rate")
        val PITCH = floatPreferencesKey("pitch")
        val LANG = stringPreferencesKey("lang")
        val DARK = booleanPreferencesKey("dark")
        val AUTO_VOICE = booleanPreferencesKey("auto_voice")
        val HANDS_FREE = booleanPreferencesKey("hands_free")
        val CLOUD = booleanPreferencesKey("cloud_tts")
        val CLOUD_MODEL = stringPreferencesKey("cloud_tts_model")
        val CLOUD_VOICE = stringPreferencesKey("cloud_voice")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
