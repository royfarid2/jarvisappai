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
    val onboarded: Boolean = false,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,                    // user | assistant | note
    val content: String,
    val ts: Long = System.currentTimeMillis(),
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id") fun observe(): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE role IN ('user','assistant') ORDER BY id DESC LIMIT :n")
    suspend fun last(n: Int): List<MessageEntity>
    @Insert suspend fun insert(m: MessageEntity): Long
    @Query("DELETE FROM messages") suspend fun clear()
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): MessageDao }
