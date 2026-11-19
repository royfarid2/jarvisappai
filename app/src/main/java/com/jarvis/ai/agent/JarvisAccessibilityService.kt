package com.jarvis.ai.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class Snapshot(val pkg: String, val nodes: List<AccessibilityNodeInfo>, val text: String)

/**
 * Screen access for Agent mode. It does nothing until the user enables it in Android Accessibility settings,
 * and JARVIS only uses it while executing a task the user typed or spoke.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Numbered, compact description of what is currently on screen. Password fields are never read. */
    fun snapshot(): Snapshot {
        val root = rootInActiveWindow ?: return Snapshot("", emptyList(), "(screen content not readable)")
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || list.size >= 220 || !n.isVisibleToUser) return
            val pwd = n.isPassword
            val t = if (pwd) "[hidden]" else n.text?.toString()?.replace('\n', ' ')?.take(90)
            val d = if (pwd) null else n.contentDescription?.toString()?.replace('\n', ' ')?.take(90)
            val labelled = !t.isNullOrBlank() || !d.isNullOrBlank()
            if (labelled || n.isEditable || n.isScrollable) {
                val kind = n.className?.toString()?.substringAfterLast('.') ?: "View"
                sb.append('[').append(list.size).append("] ").append(kind)
                if (!t.isNullOrBlank()) sb.append(" \"").append(t).append('"')
                if (!d.isNullOrBlank() && d != t) sb.append(" (").append(d).append(')')
                if (n.isClickable) sb.append(" [clickable]")
                if (n.isEditable) sb.append(" [input]")
                if (n.isScrollable) sb.append(" [scrollable]")
                if (n.isChecked) sb.append(" [checked]")
                if (n.isClickable || n.isEditable) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    sb.append(" @").append(r.centerX()).append(',').append(r.centerY())
                }
                sb.append('\n')
                list.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return Snapshot(root.packageName?.toString().orEmpty(), list, sb.toString().ifBlank { "(no readable elements)" })
    }

    fun click(s: Snapshot, i: Int): Boolean {
        val target = s.nodes.getOrNull(i) ?: return false
        target.refresh()
        var n: AccessibilityNodeInfo? = target
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); target.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun isPassword(s: Snapshot, i: Int) = s.nodes.getOrNull(i)?.isPassword == true

    fun setText(s: Snapshot, i: Int, text: String): Boolean {
        val n = s.nodes.getOrNull(i) ?: return false
        n.refresh()
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().putCharSequenceCompat(text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun Bundle.putCharSequenceCompat(t: String): Bundle {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t); return this
    }

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val node = find(root)
        if (node != null) {
            val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(a)) return true
        }
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (down) dm.heightPixels * 0.7f to dm.heightPixels * 0.3f else dm.heightPixels * 0.3f to dm.heightPixels * 0.7f
        return swipe(x, y1, x, y2)
    }

    fun global(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun longPress(s: Snapshot, i: Int): Boolean {
        val target = s.nodes.getOrNull(i) ?: return false
        target.refresh()
        var n: AccessibilityNodeInfo? = target
        while (n != null && !n.isLongClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val r = Rect(); target.getBoundsInScreen(r)
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 800)
    }

    fun tapXY(x: Float, y: Float): Boolean = tap(x, y)

    /** Press the keyboard's Enter / Search / Send key for the focused input (Android 11+). */
    fun imeEnter(s: Snapshot, i: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val n = s.nodes.getOrNull(i) ?: return false
        n.refresh()
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Finger swipe in a direction (left/right are used for side drawers, member lists, pagers...). */
    fun swipeDir(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (dir) {
            "left" -> swipe(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f)
            "right" -> swipe(w * 0.05f, h * 0.5f, w * 0.85f, h * 0.5f)
            "up" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "down" -> swipe(w / 2, h * 0.25f, w / 2, h * 0.75f)
            else -> false
        }
    }

    fun screenSize(): Pair<Int, Int> = resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 350)
    private fun gesture(p: Path, ms: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    /** JPEG (max 1024px wide) as base64, or null if unsupported (Android < 11) or it fails. */
    suspend fun screenshotB64(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (bmp == null) { cont.resume(null); return }
                        val scale = 1024f / bmp.width
                        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, 1024, (bmp.height * scale).toInt(), true) else bmp
                        val bos = ByteArrayOutputStream()
                        out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                        cont.resume(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                    } catch (e: Exception) { cont.resume(null) }
                }
                override fun onFailure(errorCode: Int) { cont.resume(null) }
            })
        }
    }

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.contains(ctx.packageName + "/", ignoreCase = true)
        }
    }
}
