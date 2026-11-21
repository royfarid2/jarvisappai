package com.jarvis.ai.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Long-term memory rules. Only explicit statements are stored ("my name is…", "remember that…", "I prefer…"),
 * never whole conversations. Duplicates are merged and changed facts (e.g. the name) replace the old one.
 */
object MemoryEngine {
    private val NAME = Regex("""(?i)\b(?:my name is|call me|i am called|i'm called)\s+([\p{L}][\p{L}'-]{1,30}(?:\s[\p{L}][\p{L}'-]{1,30})?)|(?:اسمي|ناديني|نادني)\s+([\p{L}]{2,30})""")
    private val REMEMBER = Regex("""(?is)\b(?:please\s+)?remember(?:\s+that)?\s+(.{3,300})|(?:تذكر|احفظ)\s+(?:لي\s+)?(?:أن|ان|إن)?\s*(.{3,300})""")
    private val PREFER = Regex("""(?i)\bi\s+(?:really\s+)?(?:prefer|always want|want you to)\s+(.{3,200})|\b(?:أفضل|افضل)\s+(.{3,200})""")
    private val FORGET = Regex("""(?is)\bforget\s+(?:that|about)\s+(.{3,200})|(?:انسَ|انسى|انسا)\s+(?:أن|ان|عن)?\s*(.{3,200})""")

    data class Learned(val category: String, val text: String)

    private fun clean(s: String) = s.trim().trimEnd('.', '!', '؟', '?').trim()

    /** Statements found in one user message that should be remembered. */
    fun extract(msg: String): List<Learned> {
        val out = mutableListOf<Learned>()
        NAME.find(msg)?.let { m ->
            val n = clean(m.groupValues[1].ifBlank { m.groupValues[2] })
            if (n.isNotBlank()) out += Learned("name", "The user's name is $n")
        }
        REMEMBER.find(msg)?.let { m ->
            val t = clean(m.groupValues[1].ifBlank { m.groupValues[2] })
            if (t.length >= 3) out += Learned("fact", t)
        }
        PREFER.find(msg)?.let { m ->
            val t = clean(m.groupValues[1].ifBlank { m.groupValues[2] })
            if (t.length >= 3) out += Learned("preference", "The user prefers $t")
        }
        return out
    }

    fun forgetTarget(msg: String): String? =
        FORGET.find(msg)?.let { clean(it.groupValues[1].ifBlank { it.groupValues[2] }) }?.takeIf { it.length >= 3 }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Stores what was learned: replaces the name, skips duplicates, refreshes near-duplicates. Returns how many changed. */
    suspend fun learn(dao: MessageDao, msg: String): Int {
        var changed = 0
        val existing = dao.allMemories()
        for (l in extract(msg)) {
            val now = System.currentTimeMillis()
            if (l.category == "name") {
                val old = existing.firstOrNull { it.category == "name" }
                if (old != null) { if (norm(old.text) != norm(l.text)) { dao.updateMemory(old.id, l.text, now); changed++ } }
                else { dao.addMemory(MemoryEntity(text = l.text, category = "name", createdAt = now, updatedAt = now)); changed++ }
                continue
            }
            val n = norm(l.text)
            val dup = existing.firstOrNull { val e = norm(it.text); e == n || e.contains(n) || n.contains(e) }
            if (dup != null) { dao.updateMemory(dup.id, if (l.text.length > dup.text.length) l.text else dup.text, now); continue }
            dao.addMemory(MemoryEntity(text = l.text, category = l.category, createdAt = now, updatedAt = now)); changed++
        }
        forgetTarget(msg)?.let { t ->
            val n = norm(t)
            existing.filter { norm(it.text).contains(n) }.forEach { dao.deleteMemory(it.id); changed++ }
        }
        return changed
    }

    /** The block injected into the system prompt: newest first, capped so it never crowds out the conversation. */
    fun promptBlock(mems: List<MemoryEntity>): String {
        if (mems.isEmpty()) return ""
        val sb = StringBuilder("\nThings the user asked you to remember (use them naturally, never recite the list; if the latest message just asked you to remember something it is already saved, so confirm in one short sentence):\n")
        for (m in mems.take(40)) { if (sb.length > 2500) break; sb.append("- ").append(m.text).append('\n') }
        return sb.toString()
    }

    fun toJson(mems: List<MemoryEntity>): String {
        val a = JSONArray()
        mems.forEach { a.put(JSONObject().put("text", it.text).put("category", it.category)) }
        return a.toString(2)
    }

    /** Imports an exported list; duplicates are skipped. Returns the number added, or -1 if the text is not valid. */
    suspend fun import(dao: MessageDao, json: String): Int = try {
        val a = JSONArray(json.trim())
        val existing = dao.allMemories().map { norm(it.text) }.toMutableSet()
        var added = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val t = o.optString("text").trim()
            if (t.isNotEmpty() && norm(t) !in existing) {
                val now = System.currentTimeMillis()
                dao.addMemory(MemoryEntity(text = t, category = o.optString("category", "fact").ifBlank { "fact" }, createdAt = now, updatedAt = now))
                existing += norm(t); added++
            }
        }
        added
    } catch (e: Exception) { -1 }
}
