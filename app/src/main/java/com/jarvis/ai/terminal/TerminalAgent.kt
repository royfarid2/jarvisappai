package com.jarvis.ai.terminal

import com.jarvis.ai.net.ChatMsg
import com.jarvis.ai.data.Settings
import com.jarvis.ai.net.ApiClient
import org.json.JSONObject

/** Plans and carries out a coding / file task step by step with real tool calls; every step is reported as it happens. */
class TerminalAgent(private val engine: TerminalEngine, private val api: ApiClient) {

    private val system = """You are the coding and terminal agent inside JARVIS. You work in a sandboxed workspace on an Android phone and act through tools. Reply with exactly ONE JSON object per turn and nothing else:
{"action":"write_file","path":"relative/path.ext","content":"complete file text"}
{"action":"shell","command":"..."}   (runs in the workspace with the Android shell: ls cat mkdir cp mv rm grep sed awk head tail wc find sort tar echo test. There is NO python, node, java, git, pip or compiler.)
{"action":"zip","path":"project-folder","out":"name.zip"}   (zips and saves to Downloads/JARVIS)
{"action":"done","summary":"what you built or did, honestly, including anything that could not be run"}
Rules: use relative paths only. Create every file with write_file (complete, working code, no placeholders). Check results with ls or cat. Never claim code was executed unless a shell command really ran it; if the task needs a runtime that is not available, write the code, say plainly that it could not be run here, and zip it. Always end with done. Keep summaries short. English only."""

    suspend fun run(task: String, s: Settings, onStep: (String) -> Unit, confirm: suspend (String) -> Boolean): String {
        val msgs = mutableListOf(
            ChatMsg("system", system),
            ChatMsg("user", "Task: $task\n\nFiles in the workspace now:\n${engine.tree()}"),
        )
        var bad = 0
        repeat(18) {
            val raw = api.chatOnce(s, msgs, 4000)
            val o = parse(raw)
            if (o == null) {
                if (++bad >= 3) return "I could not get a usable plan from the AI model, so nothing was changed."
                msgs += ChatMsg("assistant", raw.take(2000)); msgs += ChatMsg("user", "Reply with one valid JSON object only.")
                return@repeat
            }
            msgs += ChatMsg("assistant", o.toString())
            val result: String = when (o.optString("action")) {
                "done" -> return o.optString("summary").ifBlank { "Done." }
                "write_file" -> try {
                    val r = engine.writeFile(o.getString("path"), o.optString("content"))
                    onStep("wrote $r"); "Wrote $r"
                } catch (e: Exception) { onStep("write failed: ${e.message}"); "Error: ${e.message}" }
                "shell" -> {
                    val cmd = o.optString("command")
                    onStep("$ $cmd")
                    if (engine.refusal(cmd) == null && engine.isDestructive(cmd) && !confirm(cmd)) { onStep("declined by you"); "The user declined this command." }
                    else {
                        var shown = 0
                        val r = engine.run(cmd) { l -> if (shown++ < 25) onStep("  $l") }
                        onStep("  exit ${r.exit} · ${r.ms} ms")
                        "exit ${r.exit}\n${r.output.takeLast(3000)}"
                    }
                }
                "zip" -> try {
                    val z = engine.zipDir(o.optString("path", "."), o.optString("out", "project.zip"))
                    val where = engine.exportToDownloads(z)
                    onStep("zip ready: $where (${z.length() / 1024} KB)"); "Zip created and saved to $where"
                } catch (e: Exception) { onStep("zip failed: ${e.message}"); "Error: ${e.message}" }
                else -> "Unknown action. Use write_file, shell, zip or done."
            }
            msgs += ChatMsg("user", "Result: $result\n\nFiles now:\n${engine.tree(60)}")
        }
        return "I stopped after 18 steps before finishing. The work so far is in the workspace."
    }

    private fun parse(raw: String): JSONObject? = try {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) null else JSONObject(raw.substring(a, b + 1))
    } catch (e: Exception) { null }
}
