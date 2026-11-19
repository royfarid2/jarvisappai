package com.jarvis.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class Settings(
    val endpoint: String = "https://api.groq.com/openai/v1",
    val apiKey: String = "",
    val model: String = "openai/gpt-oss-20b",
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
    val picovoiceKey: String = "",       // Picovoice AccessKey (free) for the wake word engine
    val onboarded: Boolean = false,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
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
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") fun sessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY id") fun observe(sid: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sid AND role IN ('user','assistant') ORDER BY id DESC LIMIT :n")
    suspend fun last(sid: Long, n: Int): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sid") suspend fun count(sid: Long): Int
    @Insert suspend fun insert(m: MessageEntity): Long
    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Query("UPDATE sessions SET title = :t WHERE id = :id") suspend fun rename(id: Long, t: String)
    @Query("UPDATE sessions SET updatedAt = :ts WHERE id = :id") suspend fun touch(id: Long, ts: Long)
    @Query("DELETE FROM messages WHERE sessionId = :sid") suspend fun deleteMessages(sid: Long)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
}

@Database(entities = [MessageEntity::class, SessionEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): MessageDao }
