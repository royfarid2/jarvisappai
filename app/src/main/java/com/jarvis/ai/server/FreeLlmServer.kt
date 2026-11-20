package com.jarvis.ai.server

import android.content.Context
import android.content.Intent
import com.jarvis.ai.data.Settings
import com.jarvis.ai.net.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the settings screen shows for the FreeLLMAPI connection. */
sealed class ServerStatus {
    object Unknown : ServerStatus()
    object Checking : ServerStatus()
    data class Online(val latencyMs: Long) : ServerStatus()
    data class Offline(val reason: String) : ServerStatus()
}

/**
 * Supervises a FreeLLMAPI server.
 *
 * FreeLLMAPI is a Node.js + SQLite service, so it cannot live inside the APK. It runs either
 *  (a) on the phone under Termux, which this class can start/stop/restart through Termux's RUN_COMMAND intent, or
 *  (b) on a PC / Raspberry Pi / Docker host on your network, which this class can only monitor and reconnect to.
 * See README "FreeLLMAPI backend" and scripts/freellmapi-ctl.sh.
 */
object FreeLlmServer {
    const val TERMUX_PKG = "com.termux"
    const val CTL_SCRIPT = "/data/data/com.termux/files/home/freellmapi-ctl.sh"

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val status: StateFlow<ServerStatus> = _status

    @Volatile private var lastStartAttempt = 0L
    @Volatile private var failures = 0

    fun termuxInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TERMUX_PKG, 0); true
    } catch (e: Exception) { false }

    /** True when the endpoint points at this same phone (so Termux can be the one serving it). */
    fun isLocalEndpoint(s: Settings): Boolean {
        val e = s.endpoint.lowercase()
        return e.contains("://127.0.0.1") || e.contains("://localhost")
    }

    /** One health probe; updates [status]. Returns true when online. */
    suspend fun check(api: ApiClient, s: Settings): Boolean {
        if (s.endpoint.isBlank()) { _status.value = ServerStatus.Offline("No endpoint configured"); return false }
        if (_status.value is ServerStatus.Unknown) _status.value = ServerStatus.Checking
        val r = api.ping(s)
        return r.fold(
            onSuccess = { failures = 0; _status.value = ServerStatus.Online(it); true },
            onFailure = { failures++; _status.value = ServerStatus.Offline(it.message ?: it.javaClass.simpleName); false },
        )
    }

    /** Consecutive failed probes, used by the monitor to decide when to try a restart. */
    fun consecutiveFailures() = failures

    /** Sends start / stop / restart to the Termux control script. Returns a human-readable result. */
    fun command(ctx: Context, action: String): String {
        require(action in setOf("start", "stop", "restart"))
        if (!termuxInstalled(ctx)) return "Termux is not installed. Run FreeLLMAPI on a PC or Raspberry Pi instead (see README)."
        return try {
            val i = Intent("com.termux.RUN_COMMAND").setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", CTL_SCRIPT)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(action))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            ctx.startService(i)
            if (action == "start" || action == "restart") lastStartAttempt = System.currentTimeMillis()
            "Sent '$action' to Termux."
        } catch (e: SecurityException) {
            "Termux refused. In Termux run: echo 'allow-external-apps=true' >> ~/.termux/termux.properties, restart Termux, and allow the 'Run commands in Termux' permission for JARVIS."
        } catch (e: Exception) {
            "Could not reach Termux: ${e.message}"
        }
    }

    /** Auto-start / auto-recover. At most one attempt per 90 s so a broken install is not hammered. */
    fun maybeRecover(ctx: Context, s: Settings): Boolean {
        if (!s.autoStartServer || !isLocalEndpoint(s)) return false
        if (System.currentTimeMillis() - lastStartAttempt < 90_000) return false
        command(ctx, "start")
        return true
    }
}
