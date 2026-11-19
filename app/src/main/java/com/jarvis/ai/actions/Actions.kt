package com.jarvis.ai.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import com.jarvis.ai.JarvisNotificationListener
import com.jarvis.ai.Notifier

/** [handled] = false lets the message fall through to the AI model. */
data class ActionResult(val message: String, val success: Boolean = true, val handled: Boolean = true)

/**
 * Extensible command system. To add a command: implement [JarvisAction] (or extend [RegexAction])
 * and add it to [ActionRegistry.actions]. `match` receives normalized text and returns the regex
 * group values (index 0 = whole match) or null.
 */
interface JarvisAction {
    val id: String
    fun match(input: String): List<String>?
    suspend fun execute(ctx: Context, args: List<String>): ActionResult
}

abstract class RegexAction(
    override val id: String,
    private val patterns: List<Regex>,
    private val maxWords: Int = 40,
) : JarvisAction {
    override fun match(input: String): List<String>? {
        if (input.split(' ').size > maxWords) return null
        for (p in patterns) {
            val m = p.find(input) ?: continue
            return m.groupValues
        }
        return null
    }
}

object ActionRegistry {
    val actions: MutableList<JarvisAction> = mutableListOf(
        FlashlightAction(), AlarmAction(), ReminderAction(), BatteryAction(), VolumeAction(),
        NotificationsAction(), SettingsAction(), YouTubeSearchAction(), GoogleSearchAction(), OpenAppAction(),
    )

    fun register(action: JarvisAction) { actions.add(0, action) }

    fun normalize(s: String): String {
        var t = s.trim().lowercase().map { c ->
            when (c) {
                'أ', 'إ', 'آ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        }.joinToString("")
        t = t.replace(Regex("[\u064B-\u0652]"), "")
        t = t.replace(Regex("^(?:hey |ok |okay )?(?:jarvis|جارفس|جارفيس|جاريس)[,،:]?\\s*"), "")
        t = t.replace(Regex("^(?:(?:please|can you|could you|would you|من فضلك|لو سمحت) )+"), "")
        t = t.trim().trimEnd('.', '!', '?', '؟', '،', ',').trim()
        return t.replace(Regex("\\s+"), " ")
    }

    fun find(input: String): Pair<JarvisAction, List<String>>? {
        val n = normalize(input)
        if (n.isEmpty()) return null
        for (a in actions) {
            val m = a.match(n) ?: continue
            return a to m
        }
        return null
    }
}

private fun isAr(args: List<String>) = args.firstOrNull()?.any { it in '\u0600'..'\u06FF' } == true
private fun msg(ar: Boolean, en: String, arabic: String) = if (ar) arabic else en
private fun Context.launch(i: Intent) = startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

class FlashlightAction : RegexAction("flashlight", listOf(
    Regex("""^(?:turn|switch) (on|off) (?:the |my )?(?:flash ?light|torch)$"""),
    Regex("""^(?:flash ?light|torch) (on|off)$"""),
    Regex("""^(شغل|افتح|فعل|طفي|اطفي|اطفئ|سكر|اغلق) (?:ال)?(?:فلاش|كشاف|مصباح|ضوء الهاتف)(?: الهاتف)?$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val ar = isAr(args)
        val on = args[1] in setOf("on", "شغل", "افتح", "فعل")
        val cm = ctx.getSystemService(CameraManager::class.java)
        val cam = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult(msg(ar, "This phone doesn't seem to have a flashlight.", "ما لقيت فلاش بهذا الجهاز."), false)
        cm.setTorchMode(cam, on)
        return ActionResult(if (on) msg(ar, "Flashlight on.", "تم تشغيل الفلاش.") else msg(ar, "Flashlight off.", "تم إطفاء الفلاش."))
    }
}

class AlarmAction : RegexAction("alarm", listOf(
    Regex("""^(?:set|create|make) (?:me )?(?:an? )?alarm (?:for|at) (\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?$"""),
    Regex("""^(?:اضبط|ضبط|اعمل|سوي|شغل|حط) (?:لي )?منبه(?: على| الساعه)? ?(\d{1,2})(?::(\d{2}))?\s*(صباحا|ص|مساء|م|عصرا|ليلا)?$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val ar = isAr(args)
        var hour = args[1].toInt()
        val min = args[2].ifEmpty { "0" }.toInt()
        val pm = args[3].startsWith("p") || args[3] in setOf("مساء", "م", "عصرا", "ليلا")
        val am = args[3].startsWith("a") || args[3] in setOf("صباحا", "ص")
        if (pm && hour < 12) hour += 12
        if (am && hour == 12) hour = 0
        if (hour !in 0..23 || min !in 0..59) return ActionResult(msg(ar, "That doesn't look like a valid time.", "الوقت غير صحيح."), false)
        ctx.launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        val t = "%02d:%02d".format(hour, min)
        return ActionResult(msg(ar, "Alarm set for $t.", "تم ضبط المنبه على $t."))
    }
}

class ReminderAction : RegexAction("reminder", listOf(
    Regex("""^(?:send me a notification|notify me|remind me(?: to (.+?))?) in (\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)$"""),
    Regex("""^(?:ذكرني|نبهني|ارسل لي اشعار)(?: ان| انه| اني| ب)? ?(.*?) ?بعد (\d+) ?(ثانيه|ثواني|دقيقه|دقايق|دقائق|ساعه|ساعات)$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val ar = isAr(args)
        val n = args[2].toLong()
        val unit = args[3]
        val ms = when {
            unit.startsWith("sec") || unit.startsWith("ثان") -> n * 1000
            unit.startsWith("min") || unit.startsWith("دق") -> n * 60_000
            else -> n * 3_600_000
        }
        val text = args[1].trim().ifEmpty { msg(ar, "This is the reminder you asked for.", "هذا التذكير اللي طلبته.") }
        Notifier.schedule(ctx, text, ms)
        return ActionResult(msg(ar, "Very well. I'll notify you in $n ${args[3]}.", "تمام، راح أنبهك بعد $n ${args[3]}."))
    }
}

class BatteryAction : RegexAction("battery", listOf(
    Regex("""\bbattery\b"""),
    Regex("""(?:البطاريه|بطاريه|بطاريتي|الشحن)"""),
), maxWords = 8) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val ar = isAr(args)
        val pct = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return ActionResult(
            if (ar) "البطارية $pct%${if (charging) " وهي تشحن" else ""}."
            else "Battery is at $pct percent${if (charging) " and charging" else ""}."
        )
    }
}

class VolumeAction : RegexAction("volume", listOf(
    Regex("""^(increase|raise|turn up|decrease|lower|turn down|reduce|mute|unmute) (?:the |my )?(?:volume|sound)$"""),
    Regex("""^(?:volume|sound) (up|down)$"""),
    Regex("""^(ارفع|زيد|علي|اخفض|قلل|نزل|كتم|اكتم) (?:ال)?صوت(?: الجهاز| الهاتف)?$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val ar = isAr(args)
        val am = ctx.getSystemService(AudioManager::class.java)
        val w = args[1]
        val dir = when (w) {
            "increase", "raise", "turn up", "up", "ارفع", "زيد", "علي" -> AudioManager.ADJUST_RAISE
            "mute", "كتم", "اكتم" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> AudioManager.ADJUST_LOWER
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return ActionResult(msg(ar, "Volume is now $v of $max.", "الصوت الحين $v من $max."))
    }
}

class NotificationsAction : RegexAction("notifications", listOf(
    Regex("""^(?:show|read|check|list) (?:me )?(?:all )?(?:my |the )?notifications$"""),
    Regex("""^what(?:'s| is| are) (?:in )?my notifications$"""),
    Regex("""^(?:اقرا|اعرض|وريني|شوف|عرض|اظهر) (?:لي )?(?:ال)?اشعارات(?:ي)?$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val ar = isAr(args)
        if (!JarvisNotificationListener.isEnabled(ctx)) {
            ctx.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return ActionResult(msg(ar,
                "I need Notification Access to read your notifications. I've opened the settings page; enable JARVIS AI there and ask again.",
                "أحتاج إذن الوصول للإشعارات. فتحت لك الإعدادات، فعّل JARVIS AI وارجع اسألني."), false)
        }
        val l = JarvisNotificationListener.instance
            ?: return ActionResult(msg(ar, "Notification access is enabled but the service is still connecting. Try again in a moment.", "الخدمة لسا تتصل، جرب بعد لحظة."), false)
        val items = l.summaries()
        if (items.isEmpty()) return ActionResult(msg(ar, "You have no notifications.", "ما عندك إشعارات."))
        val top = items.take(5).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        return ActionResult(msg(ar, "You have ${items.size} notifications:\n$top", "عندك ${items.size} إشعارات:\n$top"))
    }
}

class SettingsAction : RegexAction("settings", listOf(
    Regex("""^(?:open|show) (?:the |my )?(?:android |phone )?settings$"""),
    Regex("""^(?:افتح|فتح|شغل) (?:ال)?اعدادات(?: الجهاز| الهاتف)?$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        ctx.launch(Intent(Settings.ACTION_SETTINGS))
        return ActionResult(msg(isAr(args), "Opening settings.", "أفتح الإعدادات."))
    }
}

class YouTubeSearchAction : RegexAction("youtube_search", listOf(
    Regex("""^search (?:on |in )?youtube (?:for )?(.+)$"""),
    Regex("""^search (?:for )?(.+) (?:on|in) youtube$"""),
    Regex("""^(?:play|find|look up) (.+) (?:on|in) youtube$"""),
    Regex("""^(?:ابحث|دور|فتش) (?:في|ف|على) ?يوتيوب (?:عن )?(.+)$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val q = args[1].trim()
        try {
            ctx.launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        } catch (e: Exception) {
            ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        }
        return ActionResult(msg(isAr(args), "Searching YouTube for \"$q\".", "أبحث في يوتيوب عن \"$q\"."))
    }
}

class GoogleSearchAction : RegexAction("google_search", listOf(
    Regex("""^(?:search (?:on |in )?google (?:for )?|search (?:the web |the internet |online )?(?:for )?|google |look up )(.+)$"""),
    Regex("""^(?:ابحث|دور|فتش)(?: في| على)?(?: كوكل| جوجل| قوقل| الانترنت)? ?(?:عن )?(.+)$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val q = args[1].trim()
        try {
            ctx.launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        } catch (e: Exception) {
            ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
        return ActionResult(msg(isAr(args), "Searching Google for \"$q\".", "أبحث في كوكل عن \"$q\"."))
    }
}

object AppLauncher {
    private val aliases = mapOf(
        "يوتيوب" to "youtube", "كروم" to "chrome", "واتساب" to "whatsapp", "الكاميره" to "camera",
        "الخرائط" to "maps", "تلغرام" to "telegram", "انستقرام" to "instagram", "سناب" to "snapchat",
        "تيك توك" to "tiktok", "الساعه" to "clock", "الحاسبه" to "calculator",
    )

    /** Finds an installed launcher app by (partial) label and opens it. */
    fun open(ctx: Context, raw: String): Boolean {
        val name = (aliases[ActionRegistry.normalize(raw)] ?: raw.trim()).lowercase()
        if (name.isEmpty()) return false
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        fun label(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val best = apps.firstOrNull { label(it) == name } ?: apps.firstOrNull { label(it).contains(name) } ?: return false
        val intent = pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return false
        ctx.launch(intent)
        return true
    }
}

class OpenAppAction : RegexAction("open_app", listOf(
    Regex("""^(?:open|launch) (?:the )?(?:app )?(.+)$"""),
    Regex("""^(?:افتح|فتح|شغل|شغلي) (?:تطبيق )?(.+)$"""),
)) {
    override suspend fun execute(ctx: Context, args: List<String>): ActionResult {
        val raw = args[1].trim()
        // not an installed app: let the AI answer instead
        if (!AppLauncher.open(ctx, raw)) return ActionResult("", false, handled = false)
        return ActionResult(msg(isAr(args), "Opening $raw.", "أفتح $raw."))
    }
}
