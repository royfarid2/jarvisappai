package com.jarvis.ai.net

import com.jarvis.ai.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal OpenAI-compatible client: /chat/completions (SSE streaming), /models, /audio/speech. */
class ApiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    private fun base(s: Settings) =
        s.endpoint.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')

    private fun req(s: Settings, path: String): Request.Builder {
        val b = base(s)
        require(b.startsWith("http://") || b.startsWith("https://")) { "Endpoint must start with http:// or https://" }
        return Request.Builder().url(b + path).apply {
            if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey.trim()}")
        }
    }

    private fun chatBody(s: Settings, msgs: List<Pair<String, String>>, stream: Boolean, maxTokens: Int? = null): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
        return JSONObject().put("model", s.model).put("stream", stream).put("messages", arr)
            .apply { if (maxTokens != null) put("max_tokens", maxTokens) }.toString()
    }

    fun chatStream(s: Settings, msgs: List<Pair<String, String>>): Flow<String> = flow {
        val call = http.newCall(req(s, "/chat/completions").post(chatBody(s, msgs, true).toRequestBody(json)).build())
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            val src = resp.body!!.source()
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                try {
                    val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    if (delta != null && !delta.isNull("content")) {
                        val piece = delta.optString("content", "")
                        if (piece.isNotEmpty()) emit(piece)
                    }
                } catch (e: JSONException) { /* ignore keep-alive / malformed lines */ }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Returns the list of model ids (may be empty if the server has no /models route). */
    suspend fun test(s: Settings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val models: List<String>? = try {
                http.newCall(req(s, "/models").get().build()).execute().use { r ->
                    if (!r.isSuccessful) null else {
                        val arr = JSONObject(r.body!!.string()).optJSONArray("data")
                        List(arr?.length() ?: 0) { arr!!.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }.sorted()
                    }
                }
            } catch (e: Exception) { null }
            if (models != null) return@runCatching models
            val body = chatBody(s, listOf("user" to "ping"), false, 1).toRequestBody(json)
            http.newCall(req(s, "/chat/completions").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            }
            emptyList()
        }
    }

    /** OpenAI-compatible text-to-speech. Returns an mp3 file or null on failure. */
    suspend fun speech(s: Settings, text: String, dir: File): File? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", s.cloudTtsModel).put("voice", s.cloudVoice)
            .put("input", text).put("response_format", "mp3")
            .put("speed", s.rate.toDouble().coerceIn(0.5, 1.5)).toString().toRequestBody(json)
        http.newCall(req(s, "/audio/speech").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val f = File.createTempFile("tts", ".mp3", dir)
            f.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            f
        }
    }
}
