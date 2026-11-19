package com.jarvis.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private sealed class Block {
    data class Para(val text: String) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Bullet(val text: String) : Block()
    data class Numbered(val n: String, val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Code(val lang: String, val code: String) : Block()
    data class Table(val text: String) : Block()
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*\u2022]\\s+(.*)$")
private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")

/** Small streaming-friendly markdown parser (unclosed code fences render as code while text is still arriving). */
private fun parse(src: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out.add(Block.Para(para.toString().trimEnd())); para.clear() }
    val lines = src.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        if (t.startsWith("```")) {
            flush()
            val lang = t.removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
            out.add(Block.Code(lang, code.toString().trimEnd('\n')))
            i++
            continue
        }
        val h = HEADING.find(line)
        val b = BULLET.find(line)
        val n = NUMBERED.find(line)
        when {
            h != null -> { flush(); out.add(Block.Heading(h.groupValues[1].length, h.groupValues[2])) }
            b != null -> { flush(); out.add(Block.Bullet(b.groupValues[1])) }
            n != null -> { flush(); out.add(Block.Numbered(n.groupValues[1], n.groupValues[2])) }
            t.startsWith(">") -> { flush(); out.add(Block.Quote(t.removePrefix(">").trim())) }
            t.startsWith("|") -> {
                flush()
                val tb = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { tb.append(lines[i]).append('\n'); i++ }
                out.add(Block.Table(tb.toString().trimEnd())); continue
            }
            line.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)]*)\\)")

private fun inline(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 15.sp)) { append(g[3]) }
            else -> withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(g[4]) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> Text(inline(b.text, codeBg, Cyan), style = body)
                is Block.Heading -> Text(
                    inline(b.text, codeBg, Cyan),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        else -> body.copy(fontWeight = FontWeight.Bold)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> Row {
                    Text("\u2022", style = body, color = Cyan, modifier = Modifier.width(18.dp))
                    Text(inline(b.text, codeBg, Cyan), style = body, modifier = Modifier.weight(1f))
                }
                is Block.Numbered -> Row {
                    Text(b.n + ".", style = body, color = Cyan, modifier = Modifier.widthIn(min = 24.dp))
                    Text(inline(b.text, codeBg, Cyan), style = body, modifier = Modifier.weight(1f))
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Cyan.copy(alpha = 0.6f)))
                    Text(inline(b.text, codeBg, Cyan), style = body.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                }
                is Block.Table -> Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(b.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp))
                }
                is Block.Code -> CodeBlock(b.lang, b.code)
            }
        }
    }
}

/** Boxed code with language label and a Copy button. */
@Composable
fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lang.ifBlank { "code" }.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cyan, modifier = Modifier.weight(1f))
                TextButton({ clipboard.setText(AnnotatedString(code)); copied = true }) {
                    Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(code, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
