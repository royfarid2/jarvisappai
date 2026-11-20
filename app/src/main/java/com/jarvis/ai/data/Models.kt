package com.jarvis.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class Settings(
    val endpoint: String = FREELLM_ENDPOINT,     // FreeLLMAPI is the primary backend; any OpenAI-compatible URL still works
    val apiKey: String = "",
    val model: String = "auto",                  // "auto" = FreeLLMAPI router picks and fails over between providers
    val voiceName: String = "",          // "" = auto (best British male voice)
    val rate: Float = 1.0f,
    val pitch: Float = 0.80f,
    val language: String = "en-GB",      // speech-recognition language
    val dark: Boolean = true,
    val autoVoice: Boolean = true,
    val handsFree: Boolean = false,
    val cloudTts: Boolean = true,        // use Fish Audio when a key is present
    val fishKey: String = "",
    val fishVoiceId: String = "612b878b113047d9a770c069c8b4fdfe",   // "Jarvis (MCU) J.A.R.V.I.S" on Fish Audio
    val fishModel: String = "s2.1-pro-free",
    val visionModel: String = "qwen/qwen3.8-27b",
    val agentVision: Boolean = true,     // let JARVIS send screenshots to the AI when text alone is not enough
    val hotword: Boolean = false,        // always-listening "Jarvis" wake word
    val onboarded: Boolean = false,
    // --- voice loudness (software only; the phone's own volume keys still set the hardware ceiling) ---
    val voiceVolume: Float = 1.0f,       // player volume, 0..1
    val voiceBoostDb: Int = 0,           // extra gain through LoudnessEnhancer, 0..12 dB (can distort at the top end)
    // --- wake greeting: spoken by the normal TTS voice, never a prerecorded file ---
    val greeting: String = DEFAULT_GREETING,
    val aiGreeting: Boolean = false,     // let the model write the greeting (falls back to the template if slow)
    // --- FreeLLMAPI server supervision (Termux on the phone, or just monitoring a PC/Pi on the LAN) ---
    val autoStartServer: Boolean = true,
    // --- provider choice: "groq" (own Groq key) or "freellm" (FreeLLMAPI, auto-started) ---
    val provider: String = "freellm",
    val groqKey: String = "",
    val llmKey: String = "",
)

const val DEFAULT_GREETING = "{greeting}, sir. Jarvis is online. How may I assist you?"
const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1"
const val GROQ_MODEL = "openai/gpt-oss-20b"
const val FREELLM_ENDPOINT = "http://127.0.0.1:3001/v1"

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)

@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 0,
    val role: String,                    // user | assistant | note
    val content: String,
    val ts: Long = System.currentTimeMillis(),
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC") fun sessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY id") fun observe(sid: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sid AND role IN ('user','assistant') ORDER BY id DESC LIMIT :n")
    suspend fun last(sid: Long, n: Int): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sid") suspend fun count(sid: Long): Int
    @Insert suspend fun insert(m: MessageEntity): Long
    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Query("UPDATE sessions SET title = :t WHERE id = :id") suspend fun rename(id: Long, t: String)
    @Query("UPDATE sessions SET pinned = :v WHERE id = :id") suspend fun setPinned(id: Long, v: Boolean)
    @Query("UPDATE sessions SET archived = :v WHERE id = :id") suspend fun setArchived(id: Long, v: Boolean)
    @Query("SELECT DISTINCT sessionId FROM messages WHERE role IN ('user','assistant') AND content LIKE '%' || :q || '%'") suspend fun searchSessions(q: String): List<Long>
    @Query("UPDATE sessions SET updatedAt = :ts WHERE id = :id") suspend fun touch(id: Long, ts: Long)
    @Query("DELETE FROM messages WHERE sessionId = :sid") suspend fun deleteMessages(sid: Long)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
}

@Database(entities = [MessageEntity::class, SessionEntity::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): MessageDao }

/** Keeps every existing chat: only adds the pin / archive flags. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
    }
}
