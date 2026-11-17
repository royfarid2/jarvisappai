package com.jarvis.ai.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The JARVIS arc-reactor style mark. Spins faster while [active]. */
@Composable
fun JarvisLogo(modifier: Modifier = Modifier, logoSize: Dp = 120.dp, active: Boolean = false) {
    val t = rememberInfiniteTransition(label = "logo")
    val rot by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (active) 5000 else 16000, easing = LinearEasing)), label = "rot"
    )
    val pulse by t.animateFloat(
        0.9f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    Canvas(modifier.size(logoSize)) {
        val r = this.size.minDimension / 2
        val c = center
        drawCircle(Cyan.copy(alpha = 0.10f), radius = r * 0.95f * pulse)
        val outer = Size(r * 1.8f, r * 1.8f)
        val outerTl = Offset(c.x - r * 0.9f, c.y - r * 0.9f)
        rotate(rot) {
            drawArc(Cyan, 15f, 110f, false, outerTl, outer, style = Stroke(r * 0.05f, cap = StrokeCap.Round))
            drawArc(Cyan, 195f, 110f, false, outerTl, outer, style = Stroke(r * 0.05f, cap = StrokeCap.Round))
        }
        val inner = Size(r * 1.28f, r * 1.28f)
        val innerTl = Offset(c.x - r * 0.64f, c.y - r * 0.64f)
        rotate(-rot * 1.5f) {
            drawArc(ArcBlue, 60f, 80f, false, innerTl, inner, style = Stroke(r * 0.045f, cap = StrokeCap.Round))
            drawArc(ArcBlue, 240f, 80f, false, innerTl, inner, style = Stroke(r * 0.045f, cap = StrokeCap.Round))
        }
        drawCircle(Cyan, radius = r * 0.2f * pulse)
        drawCircle(Color(0xFF0A0B0D), radius = r * 0.11f)
    }
}

/** Animated bar waveform. [level] 0..1 drives amplitude (mic level or a constant while speaking). */
@Composable
fun Waveform(level: Float, modifier: Modifier = Modifier, color: Color = Cyan) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase"
    )
    val amp by animateFloatAsState(level.coerceIn(0f, 1f), label = "amp")
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val n = 34
        val gap = size.width / n
        for (i in 0 until n) {
            val envelope = sin(i.toFloat() / (n - 1) * PI.toFloat()).coerceAtLeast(0.15f)
            val h = (0.10f + 0.90f * amp * abs(sin(phase + i * 0.55f))) * envelope
            val bh = size.height * h
            val x = gap * i + gap / 2
            drawLine(color, Offset(x, size.height / 2 - bh / 2), Offset(x, size.height / 2 + bh / 2),
                strokeWidth = gap * 0.42f, cap = StrokeCap.Round)
        }
    }
}

/** Faint HUD backdrop: grid + slowly rotating rings, like Stark's interface. */
@Composable
fun HudBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "hud")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(60000, easing = LinearEasing)), label = "hudrot")
    Canvas(modifier) {
        val step = 36.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(Cyan.copy(alpha = 0.035f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(Cyan.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        val c = Offset(size.width / 2, size.height * 0.34f)
        val r = size.width * 0.62f
        rotate(rot, c) {
            drawArc(Cyan.copy(alpha = 0.10f), 0f, 70f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2f))
            drawArc(Cyan.copy(alpha = 0.10f), 180f, 70f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2f))
        }
        rotate(-rot * 1.3f, c) {
            val r2 = r * 0.72f
            drawArc(ArcBlue.copy(alpha = 0.12f), 90f, 50f, false, Offset(c.x - r2, c.y - r2), Size(r2 * 2, r2 * 2), style = Stroke(2f))
            drawArc(ArcBlue.copy(alpha = 0.12f), 270f, 50f, false, Offset(c.x - r2, c.y - r2), Size(r2 * 2, r2 * 2), style = Stroke(2f))
        }
        drawCircle(Cyan.copy(alpha = 0.05f), r * 0.45f, c, style = Stroke(1.5f))
    }
}
