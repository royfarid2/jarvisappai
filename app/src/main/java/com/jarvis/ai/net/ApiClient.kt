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

/** A chat message; [imageB64] (JPEG, base64) is sent as an image for vision models. */
data class ChatMsg(val role: String, val content: String, val imageB64: String? = null)

/** OpenAI-compatible client (Groq etc.): streaming chat, one-shot chat, /models, plus Fish Audio TTS. */
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

    private fun msgJson(m: ChatMsg): JSONObject =
        if (m.imageB64 == null) JSONObject().put("role", m.role).put("content", m.content)
        else JSONObject().put("role", m.role).put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", m.content))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + m.imageB64))))

    private fun chatBody(s: Settings, msgs: List<ChatMsg>, stream: Boolean, maxTokens: Int? = null): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(msgJson(it)) }
        return JSONObject().put("model", s.model).put("stream", stream).put("messages", arr)
            .apply {
                if (maxTokens != null) put("max_tokens", maxTokens)
                // gpt-oss models "think" first; keep it short so voice replies start fast
                if (s.model.contains("gpt-oss") && s.endpoint.contains("groq")) put("reasoning_effort", "low")
            }.toString()
    }

    fun chatStream(s: Settings, msgs: List<ChatMsg>): Flow<String> = flow {
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

    /** Non-streaming completion; returns the assistant text. */
    suspend fun chatOnce(s: Settings, msgs: List<ChatMsg>, maxTokens: Int = 1500): String = withContext(Dispatchers.IO) {
        http.newCall(req(s, "/chat/completions").post(chatBody(s, msgs, false, maxTokens).toRequestBody(json)).build())
            .execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${txt.take(300)}")
                JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
            }
    }

    /**
     * FreeLLMAPI liveness probe: GET <origin>/api/ping (no auth needed). Success = round-trip time in ms.
     * Any other server (Groq, OpenAI...) has no such route, so it reports as "not a FreeLLMAPI server".
     */
    suspend fun ping(s: Settings): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val b = base(s)
            require(b.startsWith("http://") || b.startsWith("https://")) { "Endpoint must start with http:// or https://" }
            val origin = b.removeSuffix("/v1").trimEnd('/')
            val t0 = System.nanoTime()
            val quick = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
            quick.newCall(Request.Builder().url("$origin/api/ping").get().build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                if (JSONObject(r.body?.string().orEmpty()).optString("status") != "ok") throw IOException("unexpected ping reply")
            }
            (System.nanoTime() - t0) / 1_000_000
        }
    }

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
            val body = chatBody(s, listOf(ChatMsg("user", "ping")), false, 1).toRequestBody(json)
            http.newCall(req(s, "/chat/completions").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            }
            emptyList()
        }
    }

    /**
     * Fish Audio text-to-speech (https://api.fish.audio/v1/tts) using the configured voice model id.
     * Tries the configured model first, then the other S2.1 tiers (free vs paid accounts). Returns an mp3 or null.
     */
    suspend fun speech(s: Settings, text: String, dir: File): File? = withContext(Dispatchers.IO) {
        if (s.fishKey.isBlank()) return@withContext null
        val body = JSONObject().put("text", text).put("format", "mp3")
            .put("prosody", JSONObject().put("speed", s.rate.toDouble().coerceIn(0.5, 2.0)))
            .apply { if (s.fishVoiceId.isNotBlank()) put("reference_id", s.fishVoiceId.trim()) }
            .toString()
        for (model in listOf(s.fishModel, "s2.1-pro-free", "s2.1-pro").filter { it.isNotBlank() }.distinct()) {
            val req = Request.Builder().url("https://api.fish.audio/v1/tts")
                .header("Authorization", "Bearer ${s.fishKey.trim()}")
                .header("model", model)
                .post(body.toRequestBody(json)).build()
            val f = try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else {
                        val out = File.createTempFile("tts", ".mp3", dir)
                        out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                        out
                    }
                }
            } catch (e: IOException) { null }
            if (f != null) return@withContext f
        }
        null
    }
}
