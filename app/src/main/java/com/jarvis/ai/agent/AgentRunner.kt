package com.jarvis.ai.agent

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
 * Observe -> decide -> act loop (up to 40 steps). Each step the AI sees the numbered on-screen elements with
 * coordinates (plus a screenshot when text alone is not enough) and answers with one JSON action.
 * Safety: user-started only, Stop button, passwords are never read or typed, money/destructive taps always need
 * approval, and "send/post" taps need approval unless the user's own instruction asked to send something.
 */
class AgentRunner(private val ctx: Context, private val api: ApiClient, private val speak: (String) -> Unit) {

    private class Act(
        val action: String, val index: Int, val text: String, val direction: String, val app: String,
        val message: String, val url: String, val x: Int, val y: Int,
    )

    suspend fun run(task: String, s: Settings, log: (String) -> Unit): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "The JARVIS accessibility service isn't running. Enable it in Android Settings > Accessibility, then try again."
        val sendAllowed = SEND_INTENT.containsMatchIn(task.lowercase())
        val history = ArrayList<String>()
        var lastScreen = ""
        var sameCount = 0
        var wantShot = false
        val (sw, sh) = svc.screenSize()

        for (step in 1..MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            if (AgentControl.stop) return "Stopped as requested."

            var snap = svc.snapshot()
            var tries = 0
            while (snap.nodes.isEmpty() && snap.text.startsWith("(screen content") && tries++ < 3) { delay(500); snap = svc.snapshot() }
            if (snap.text == lastScreen) sameCount++ else { sameCount = 0; lastScreen = snap.text }

            val shot = if (s.agentVision && (wantShot || snap.nodes.size < 5 || sameCount >= 2)) svc.screenshotB64() else null
            wantShot = false
            val prompt = buildString {
                append("TASK: ").append(task).append("\n\nPREVIOUS ACTIONS:\n")
                if (history.isEmpty()) append("(none)\n") else history.takeLast(14).forEachIndexed { i, h -> append(i + 1).append(". ").append(h).append('\n') }
                append("\nSTEP ").append(step).append(" of ").append(MAX_STEPS)
                append("  |  SCREEN SIZE: ").append(sw).append('x').append(sh)
                append("  |  CURRENT APP PACKAGE: ").append(snap.pkg.ifBlank { "unknown" })
                if (sameCount >= 2) append("\nWARNING: the screen has not changed after your last actions. Try something different.")
                append("\nSCREEN ELEMENTS (index, type, text, flags, @x,y centre):\n").append(snap.text)
                if (shot != null) append("\n(A screenshot of the current screen is attached.)")
            }
            val reply = try {
                api.chatOnce(if (shot != null) s.copy(model = s.visionModel) else s, listOf(ChatMsg("system", SYSTEM), ChatMsg("user", prompt, shot)), 2000)
            } catch (e: Exception) {
                if (shot != null) {   // vision model unavailable: retry this step with text only
                    try { api.chatOnce(s, listOf(ChatMsg("system", SYSTEM), ChatMsg("user", prompt.replace("(A screenshot of the current screen is attached.)", "")))) }
                    catch (e2: Exception) { return "I lost contact with the AI service: ${e2.message}" }
                } else return "I lost contact with the AI service: ${e.message}"
            }
            val a = parse(reply)
            if (a == null) { history.add("(my last reply was not valid JSON; reply with one JSON object only)"); log("Step $step: re-thinking"); continue }

            when (a.action) {
                "done", "ask" -> { log("Step $step: ${a.action}"); return a.message.ifBlank { "Done." } }
                "open_app" -> {
                    log("Step $step: opening ${a.app}")
                    val ok = AppLauncher.open(ctx, a.app)
                    history.add("open_app ${a.app} -> ${if (ok) "ok" else "app not found"}")
                    delay(2000)
                }
                "open_url" -> {
                    log("Step $step: opening link")
                    val ok = try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                    } catch (e: Exception) { false }
                    history.add("open_url ${a.url.take(80)} -> ${if (ok) "ok" else "no app can open it"}")
                    delay(2000)
                }
                "play_music" -> {
                    log("Step $step: play \"${a.text.take(30)}\"")
                    val pkg = if (a.app.isBlank()) "com.spotify.music" else (AppLauncher.packageFor(ctx, a.app) ?: "com.spotify.music")
                    val ok = try {
                        ctx.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .setPackage(pkg).putExtra(SearchManager.QUERY, a.text)
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                    } catch (e: Exception) { false }
                    history.add("play_music \"${a.text.take(30)}\" in $pkg -> ${if (ok) "launched, verify it is playing" else "not supported, use the app UI instead"}")
                    delay(3000)
                }
                "click" -> {
                    val node = snap.nodes.getOrNull(a.index)
                    val label = node?.let { (it.text?.toString() ?: it.contentDescription?.toString()).orEmpty() }.orEmpty()
                    log("Step $step: tap [${a.index}] ${label.take(30)}")
                    if (node == null) { history.add("click ${a.index} -> no such element"); continue }
                    if (!approved(label, snap.pkg, sendAllowed)) return "I didn't tap \"${label.take(40)}\" because you didn't approve it."
                    val ok = svc.click(snap, a.index)
                    history.add("click [${a.index}] \"${label.take(30)}\" -> ${if (ok) "ok" else "failed"}")
                    delay(1100)
                }
                "click_xy" -> {
                    log("Step $step: tap at ${a.x},${a.y}")
                    val ok = svc.tapXY(a.x.toFloat(), a.y.toFloat())
                    history.add("click_xy ${a.x},${a.y} -> ${if (ok) "ok" else "failed"}")
                    delay(1100)
                }
                "long_press" -> {
                    val label = snap.nodes.getOrNull(a.index)?.let { (it.text?.toString() ?: it.contentDescription?.toString()).orEmpty() }.orEmpty()
                    log("Step $step: long press [${a.index}] ${label.take(30)}")
                    history.add("long_press [${a.index}] \"${label.take(30)}\" -> ${if (svc.longPress(snap, a.index)) "ok" else "failed"}")
                    delay(1000)
                }
                "type" -> {
                    log("Step $step: typing into [${a.index}]")
                    if (svc.isPassword(snap, a.index)) return "That's a password field. I won't type passwords. Please enter it yourself."
                    val ok = svc.setText(snap, a.index, a.text)
                    history.add("type [${a.index}] \"${a.text.take(40)}\" -> ${if (ok) "ok" else "failed"}")
                    delay(700)
                }
                "enter" -> {
                    log("Step $step: pressing enter")
                    val label = snap.nodes.getOrNull(a.index)?.text?.toString().orEmpty()
                    if (sendAllowed || !SEND_LIKE.containsMatchIn(label.lowercase())) {
                        history.add("enter on [${a.index}] -> ${if (svc.imeEnter(snap, a.index)) "ok" else "failed (click the search/send button instead)"}")
                    } else history.add("enter on [${a.index}] -> skipped")
                    delay(1200)
                }
                "scroll" -> {
                    log("Step $step: scroll ${a.direction}")
                    history.add("scroll ${a.direction} -> ${if (svc.scroll(a.direction != "up")) "ok" else "failed"}")
                    delay(800)
                }
                "swipe" -> {
                    log("Step $step: swipe ${a.direction}")
                    history.add("swipe ${a.direction} -> ${if (svc.swipeDir(a.direction)) "ok" else "failed"}")
                    delay(900)
                }
                "back", "home", "recents", "notifications" -> {
                    log("Step $step: ${a.action}")
                    history.add("${a.action} -> ${if (svc.global(a.action)) "ok" else "failed"}")
                    delay(900)
                }
                "look" -> { log("Step $step: looking at the screen"); wantShot = true; history.add("look (screenshot requested)") }
                "wait" -> { log("Step $step: waiting"); history.add("wait"); delay(1800) }
                else -> history.add("unknown action ${a.action}")
            }
        }
        return "I ran out of steps before finishing. Tell me to continue, or give me a more specific instruction."
    }

    /** Money/destructive taps always need approval; send/post taps only when the user didn't ask to send. */
    private suspend fun approved(label: String, pkg: String, sendAllowed: Boolean): Boolean {
        val l = label.lowercase()
        val risky = HIGH_RISK.containsMatchIn(l) || (!sendAllowed && SEND_LIKE.containsMatchIn(l))
        if (!risky) return true
        return askConfirm("JARVIS wants to tap \"${label.take(40)}\" in ${pkg.substringAfterLast('.')}. Allow?")
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
        Act(o.optString("action").lowercase(), o.optInt("index", -1), o.optString("text"), o.optString("direction").lowercase(),
            o.optString("app"), o.optString("message"), o.optString("url"), o.optInt("x", -1), o.optInt("y", -1))
    } catch (e: Exception) { null }

    private companion object {
        const val MAX_STEPS = 40
        val HIGH_RISK = Regex(
            "\\b(pay|buy|purchase|place order|checkout|subscribe|donate|transfer|delete|erase|reset|uninstall|sign out|log out|logout|ban|kick|unfriend|factory)\\b" +
                "|دفع|شراء|اشتري|حذف|تحويل|مسح|حظر"
        )
        val SEND_LIKE = Regex("\\b(send|post|publish|submit|share|reply|tweet|confirm)\\b|ارسال|إرسال|ارسل|نشر|تأكيد")
        val SEND_INTENT = Regex("\\b(send|message|msg|text|dm|reply|post|tweet|share|tell|say|write|email|whatsapp|answer)\\b|ارسل|رسال|رد |انشر|قل ")

        const val SYSTEM = """You are the hands of JARVIS, an expert operator of an Android phone, controlling it through accessibility: you can open any app, read any screen, tap, type, swipe, scroll and navigate across apps (Discord, Spotify, YouTube, WhatsApp, Telegram, Instagram, Chrome, Settings, anything). You also retrieve information (counts, names, messages, prices...) and report it. The user's accounts are already logged in.
Each turn you get the TASK, your PREVIOUS ACTIONS and the current SCREEN ELEMENTS (numbered, with @x,y centres), sometimes with a screenshot. The screen may show the JARVIS app itself: ignore it and use open_app / home.
Reply with exactly ONE JSON object and nothing else:
{"thought":"short reasoning","action":"...","index":0,"text":"","direction":"","app":"","url":"","x":0,"y":0,"message":""}
Actions:
- open_app {app}: launch an app by name (preferred way to start).
- open_url {url}: deep links and web links. Useful: spotify:search:<query>, https://www.youtube.com/results?search_query=<q>, https://wa.me/<number>?text=<msg>, sms:<number>?body=<msg>, tel:<number>, geo:0,0?q=<place>, tg://resolve?domain=<user>, https://discord.com/channels/@me
- play_music {text:"song or artist", app:"Spotify"}: ask the music app to search and play; then verify on screen that it is playing, otherwise use the UI.
- click {index} / long_press {index}: act on an element. click_xy {x,y}: tap coordinates when nothing suitable is listed (use the screenshot).
- type {index, text}: set text of an [input] element. enter {index}: press the keyboard Search/Send/Enter key of that input.
- scroll {direction:"down"|"up"}: scroll the main list. swipe {direction:"left"|"right"|"up"|"down"}: finger swipe. Side menus often open with swipe right from the left edge (e.g. server/channel lists), member lists with swipe left, tab pagers with left/right.
- back, home, recents, notifications, wait (screen loading), look (get a screenshot next turn).
- done {message}: finished; put the answer or a short summary in "message". ask {message}: you are blocked and need the user (login, captcha, password, payment, OTP, ambiguity).
Method: think about what screen you need, navigate there, read the values carefully from SCREEN ELEMENTS, and verify the result of important actions on the next screen before saying done. For information tasks, find the exact figure on screen and state it in "message" (for example the number next to "Members"). Look in menus, headers, profile/server info and settings; scroll to find things; open overflow menus (⋮ or ...) when an option isn't visible. Dismiss pop-ups, tutorials and cookie banners when they block you; grant ordinary Android permission prompts needed for the task.
When asked to send a message, open the right chat, type the exact text, then click Send. Do not change what the user asked you to write.
Never type passwords, card numbers or verification codes. Never buy, pay, transfer money or delete things unless the task explicitly asks (the user will be asked to approve). If an action failed or the screen didn't change, try a different element or approach; never repeat the same failing action more than twice.
Always write "message" in English, clearly and briefly, as JARVIS would speak."""
    }
}
