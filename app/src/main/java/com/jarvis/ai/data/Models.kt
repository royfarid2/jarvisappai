package com.jarvis.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Endpoint, key and model remembered per AI provider, so switching provider never loses the others' keys. */
data class AiProfile(val endpoint: String, val apiKey: String, val model: String)

data class Settings(
    val provider: String = "groq",                       // groq | freellm | custom
    val profiles: Map<String, AiProfile> = emptyMap(),
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
    val onboarded: Boolean = false,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
)

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
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun memories(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") suspend fun allMemories(): List<MemoryEntity>
    @Insert suspend fun addMemory(m: MemoryEntity): Long
    @Query("UPDATE memories SET text = :t, updatedAt = :ts WHERE id = :id") suspend fun updateMemory(id: Long, t: String, ts: Long)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: Long)
    @Query("DELETE FROM memories") suspend fun clearMemories()
    @Query("UPDATE sessions SET pinned = :v WHERE id = :id") suspend fun setPinned(id: Long, v: Boolean)
    @Query("UPDATE sessions SET archived = :v WHERE id = :id") suspend fun setArchived(id: Long, v: Boolean)
    @Query("SELECT DISTINCT sessionId FROM messages WHERE role IN ('user','assistant') AND content LIKE '%' || :q || '%'") suspend fun searchSessions(q: String): List<Long>
    @Query("UPDATE sessions SET updatedAt = :ts WHERE id = :id") suspend fun touch(id: Long, ts: Long)
    @Query("DELETE FROM messages WHERE sessionId = :sid") suspend fun deleteMessages(sid: Long)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
}

@Database(entities = [MessageEntity::class, SessionEntity::class, MemoryEntity::class], version = 4, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): MessageDao }

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
    }
}

/** Keeps every existing chat: only adds the pin / archive flags. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
    }
}
