package com.jarvis.ai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.ai.Notifier
import com.jarvis.ai.actions.AppLauncher
import com.jarvis.ai.data.Settings
import com.jarvis.ai.net.ApiClient
import com.jarvis.ai.net.ChatMsg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object AgentControl {
    @Volatile var stop = false
    @Volatile var confirm: CompletableDeferred<Boolean>? = null
    const val ACTION_STOP = "com.jarvis.ai.AGENT_STOP"
    const val ACTION_ALLOW = "com.jarvis.ai.AGENT_ALLOW"
    const val ACTION_DENY = "com.jarvis.ai.AGENT_DENY"
}

/** Handles the Stop / Allow / Deny buttons on the agent notifications. */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AgentControl.ACTION_STOP -> { AgentControl.stop = true; AgentControl.confirm?.complete(false) }
            AgentControl.ACTION_ALLOW -> AgentControl.confirm?.complete(true)
            AgentControl.ACTION_DENY -> AgentControl.confirm?.complete(false)
        }
    }
}

/**
 * Observe -> decide -> act loop. Each step the AI sees the numbered on-screen elements (optionally a screenshot)
 * and answers with one JSON action. Safety: user-started only, step limit, Stop button, passwords are never read or
 * typed, and sensitive taps (send, pay, delete...) need the user's approval via a notification.
 */
class AgentRunner(private val ctx: Context, private val api: ApiClient, private val speak: (String) -> Unit) {

    private class Act(val action: String, val index: Int, val text: String, val direction: String, val app: String, val message: String, val thought: String)

    suspend fun run(task: String, s: Settings, log: (String) -> Unit): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "The JARVIS accessibility service isn't running. Enable it in Android Settings > Accessibility, then try again."
        val history = ArrayList<String>()
        for (step in 1..MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            if (AgentControl.stop) return "Stopped as requested."
            val snap = svc.snapshot()
            val shot = if (s.agentVision && snap.nodes.size < 4) svc.screenshotB64() else null
            val prompt = buildString {
                append("TASK: ").append(task).append("\n\nPREVIOUS ACTIONS:\n")
                if (history.isEmpty()) append("(none)\n") else history.takeLast(10).forEachIndexed { i, h -> append(i + 1).append(". ").append(h).append('\n') }
                append("\nCURRENT APP PACKAGE: ").append(snap.pkg.ifBlank { "unknown" })
                append("\nSCREEN ELEMENTS:\n").append(snap.text)
                if (shot != null) append("\n(A screenshot is attached.)")
            }
            val reply = try {
                api.chatOnce(if (shot != null) s.copy(model = s.visionModel) else s, listOf(ChatMsg("system", SYSTEM), ChatMsg("user", prompt, shot)))
            } catch (e: Exception) { return "I lost contact with the AI service: ${e.message}" }
            val a = parse(reply)
            if (a == null) { history.add("(my last reply was not valid JSON)"); log("Step $step: re-thinking"); continue }

            when (a.action) {
                "done", "ask" -> { log("Step $step: ${a.action}"); return a.message.ifBlank { "Done." } }
                "open_app" -> {
                    log("Step $step: opening ${a.app}")
                    val ok = AppLauncher.open(ctx, a.app)
                    history.add("open_app ${a.app} -> ${if (ok) "ok" else "app not found"}")
                    delay(1800)
                }
                "click" -> {
                    val node = snap.nodes.getOrNull(a.index)
                    val label = node?.let { (it.text?.toString() ?: it.contentDescription?.toString()).orEmpty() }.orEmpty()
                    log("Step $step: tap [${a.index}] ${label.take(30)}")
                    if (node == null) { history.add("click ${a.index} -> no such element"); continue }
                    if (SENSITIVE.containsMatchIn(label.lowercase())) {
                        val allowed = askConfirm("JARVIS wants to tap \"${label.take(40)}\" in ${snap.pkg.substringAfterLast('.')}. Allow?")
                        if (!allowed) return "I didn't tap \"${label.take(40)}\" because you didn't approve it."
                    }
                    val ok = svc.click(snap, a.index)
                    history.add("click [${a.index}] \"${label.take(30)}\" -> ${if (ok) "ok" else "failed"}")
                    delay(1100)
                }
                "type" -> {
                    log("Step $step: typing into [${a.index}]")
                    if (svc.isPassword(snap, a.index)) return "That's a password field. I won't type passwords. Please enter it yourself."
                    val ok = svc.setText(snap, a.index, a.text)
                    history.add("type [${a.index}] \"${a.text.take(30)}\" -> ${if (ok) "ok" else "failed"}")
                    delay(700)
                }
                "scroll" -> {
                    log("Step $step: scroll ${a.direction}")
                    val ok = svc.scroll(a.direction != "up")
                    history.add("scroll ${a.direction} -> ${if (ok) "ok" else "failed"}")
                    delay(800)
                }
                "back", "home", "recents", "notifications" -> {
                    log("Step $step: ${a.action}")
                    history.add("${a.action} -> ${if (svc.global(a.action)) "ok" else "failed"}")
                    delay(900)
                }
                "wait" -> { log("Step $step: waiting"); history.add("wait"); delay(1800) }
                else -> history.add("unknown action ${a.action}")
            }
        }
        return "I ran out of steps before finishing. Tell me to continue, or give me a more specific instruction."
    }

    private suspend fun askConfirm(question: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        AgentControl.confirm = d
        if (!Notifier.askConfirm(ctx, question)) return false     // can't ask -> safest answer is no
        speak("Sir, $question")
        val r = withTimeoutOrNull(60_000) { d.await() } ?: false
        Notifier.cancel(ctx, Notifier.ID_CONFIRM)
        AgentControl.confirm = null
        return r
    }

    private fun parse(text: String): Act? = try {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        val o = JSONObject(text.substring(a, b + 1))
        Act(o.optString("action").lowercase(), o.optInt("index", -1), o.optString("text"), o.optString("direction"),
            o.optString("app"), o.optString("message"), o.optString("thought"))
    } catch (e: Exception) { null }

    private companion object {
        const val MAX_STEPS = 20
        val SENSITIVE = Regex(
            "\\b(send|pay|buy|purchase|order|checkout|delete|remove|transfer|confirm|post|publish|submit|sign out|log out|uninstall|erase|reset)\\b" +
                "|ارسال|إرسال|ارسل|دفع|شراء|اشتري|حذف|تحويل|تاكيد|تأكيد|نشر|طلب|تسجيل الخروج|مسح"
        )
        const val SYSTEM = """You are the hands of JARVIS, controlling an Android phone through accessibility. Each turn you receive the TASK, your PREVIOUS ACTIONS and the numbered SCREEN ELEMENTS of the current screen (the screen may show the JARVIS app itself: ignore it and use open_app or home).
Reply with exactly ONE JSON object and nothing else:
{"thought":"short reasoning","action":"click|type|scroll|back|home|open_app|wait|done|ask","index":0,"text":"","direction":"up|down","app":"","message":""}
- click/type use "index" from SCREEN ELEMENTS. type puts "text" into an [input] element.
- open_app uses "app" (the app's name, e.g. "YouTube"). Prefer it to launch apps.
- After typing into a search box, click the search/submit element (or the keyboard search button) to run it.
- done: the task is finished, put a short summary for the user in "message" (same language as the task).
- ask: you need information or the user must act (login, captcha, password, payment, OTP). Explain in "message".
- Never type passwords, card numbers or verification codes. Do not send, buy, delete or post unless the task explicitly asks for it.
- If an action failed or the screen didn't change, try a different element or approach; don't repeat the same action more than twice."""
    }
}
