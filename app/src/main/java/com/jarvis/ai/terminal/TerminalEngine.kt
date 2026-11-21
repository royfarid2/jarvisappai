package com.jarvis.ai.terminal

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Real command execution inside an isolated workspace (app-private storage, no root, no other apps' data).
 * Commands run with the Android shell (toybox: ls cat mkdir cp mv rm grep sed awk find tar ...); there is no Python,
 * Node, Git or compiler in this environment. Paths outside the workspace and system commands are refused.
 */
class TerminalEngine(private val ctx: Context) {
    val workspace: File = File(ctx.filesDir, "workspace").also { it.mkdirs() }
    private val wsPath = workspace.canonicalPath

    data class Exec(val command: String, val output: String, val exit: Int, val ms: Long)

    /** Command history, newest last (also appended to workspace/.history.log). */
    val history = ArrayList<Exec>()
    @Volatile private var current: Process? = null

    fun cancel() { try { current?.destroyForcibly() } catch (e: Exception) { } }

    fun safe(rel: String): File {
        val f = File(workspace, rel.trimStart('/')).canonicalFile
        require(f.path == wsPath || f.path.startsWith(wsPath + File.separator)) { "Path escapes the workspace: $rel" }
        return f
    }

    private val allowedAbs = listOf("/dev/null", "/system/bin/", "/system/xbin/")
    private val systemCmds = Regex("""(?<![\w./-])(su|sudo|pm|am|settings|reboot|svc|wm|dumpsys|input|screencap|setprop|chmod|chown|mount|insmod)(?![\w-])""")

    /** Returns why a command is refused, or null if it may run. */
    fun refusal(cmd: String): String? {
        if (cmd.contains("../")) return "Parent-directory paths (../) are not allowed."
        Regex("""(?<![\w.$-])/[A-Za-z0-9_./-]+""").findAll(cmd).forEach { m ->
            val p = m.value
            if (!p.startsWith(wsPath) && allowedAbs.none { p.startsWith(it) }) return "Path outside the workspace: $p"
        }
        if (systemCmds.containsMatchIn(cmd)) return "System-level commands are not allowed in the workspace."
        return null
    }

    fun isDestructive(cmd: String) = Regex("""(?<![\w-])(rm|dd|shred|truncate)(?![\w-])""").containsMatchIn(cmd)

    suspend fun run(cmd: String, cwdRel: String = "", timeoutSec: Long = 120, onLine: (String) -> Unit = {}): Exec =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            refusal(cmd)?.let { return@withContext record(Exec(cmd, "Refused: $it", 126, 0)) }
            val dir = try { safe(cwdRel).takeIf { it.isDirectory } ?: workspace } catch (e: Exception) { workspace }
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(dir).redirectErrorStream(true)
            pb.environment().apply { put("HOME", wsPath); put("TMPDIR", ctx.cacheDir.path); put("PATH", "/system/bin:/system/xbin") }
            val p = try { pb.start() } catch (e: Exception) { return@withContext record(Exec(cmd, "Could not start: ${e.message}", 127, 0)) }
            current = p
            val sb = StringBuilder()
            val reader = Thread { try { p.inputStream.bufferedReader().forEachLine { l -> if (sb.length < 20_000) sb.appendLine(l); onLine(l) } } catch (e: Exception) { } }
            reader.start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            reader.join(2000)
            current = null
            var out = sb.toString().trimEnd()
            if (!finished) out += "\n[stopped after ${timeoutSec}s]"
            record(Exec(cmd, out, if (finished) p.exitValue() else 124, System.currentTimeMillis() - t0))
        }

    private fun record(e: Exec): Exec {
        synchronized(history) { history.add(e); if (history.size > 300) history.removeAt(0) }
        try { File(workspace, ".history.log").appendText("$ ${e.command}\n[exit ${e.exit}, ${e.ms} ms]\n") } catch (ex: Exception) { }
        return e
    }

    fun writeFile(rel: String, content: String): String {
        val f = safe(rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "${f.relativeTo(workspace).path} (${f.length()} bytes)"
    }

    fun tree(max: Int = 150): String {
        val files = workspace.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.path.contains("/exports/") }
            .map { it.relativeTo(workspace).path }.take(max).toList()
        return if (files.isEmpty()) "(empty)" else files.joinToString("\n")
    }

    /** Zips a workspace folder into workspace/exports and returns the file. */
    fun zipDir(rel: String, outName: String): File {
        val src = safe(rel)
        require(src.exists()) { "Nothing at $rel" }
        val outDir = File(workspace, "exports").also { it.mkdirs() }
        val out = File(outDir, outName.substringAfterLast('/').let { if (it.endsWith(".zip")) it else "$it.zip" })
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val base = if (src.isDirectory) src else src.parentFile!!
            (if (src.isDirectory) src.walkTopDown().filter { it.isFile } else sequenceOf(src))
                .filter { !it.canonicalPath.startsWith(outDir.canonicalPath) && !it.name.startsWith(".history") }
                .forEach { f -> z.putNextEntry(ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        return out
    }

    /** Extracts a zip that is inside the workspace; entries that would escape the destination are skipped. */
    fun unzip(zipRel: String, destRel: String): Int {
        val dest = safe(destRel).also { it.mkdirs() }
        var n = 0
        ZipInputStream(safe(zipRel).inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(dest, e.name).canonicalFile
                if (f.path.startsWith(dest.canonicalPath + File.separator)) {
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }; n++ }
                }
                e = z.nextEntry
            }
        }
        return n
    }

    /** Copies a file to the public Downloads/JARVIS folder (API 29+) or the app's external files folder. Returns where it went. */
    fun exportToDownloads(f: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/JARVIS")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Could not create the file in Downloads")
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            return "Downloads/JARVIS/${f.name}"
        }
        val d = File(ctx.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        f.copyTo(File(d, f.name), true)
        return d.path + "/" + f.name
    }
}
