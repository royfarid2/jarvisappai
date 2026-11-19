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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The JARVIS mark: ticked outer ring, counter-rotating segments, reactor triangle and a glowing core. */
@Composable
fun JarvisLogo(modifier: Modifier = Modifier, logoSize: Dp = 120.dp, active: Boolean = false) {
    val t = rememberInfiniteTransition(label = "logo")
    val rot by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (active) 6000 else 20000, easing = LinearEasing)), label = "rot"
    )
    val pulse by t.animateFloat(
        0.85f, 1f,
        infiniteRepeatable(tween(if (active) 700 else 1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    Canvas(modifier.size(logoSize)) {
        val r = this.size.minDimension / 2
        val c = center
        fun ring(rad: Float) = Pair(Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2))
        // soft halo
        drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.28f * pulse), Color.Transparent), c, r), r)
        // outer tick ring
        rotate(rot, c) {
            for (i in 0 until 60) {
                val ang = Math.toRadians(i * 6.0)
                val long = i % 5 == 0
                val r1 = r * (if (long) 0.84f else 0.89f)
                val r2 = r * 0.95f
                drawLine(
                    Cyan.copy(alpha = if (long) 0.95f else 0.45f),
                    Offset(c.x + (r1 * kotlin.math.cos(ang)).toFloat(), c.y + (r1 * kotlin.math.sin(ang)).toFloat()),
                    Offset(c.x + (r2 * kotlin.math.cos(ang)).toFloat(), c.y + (r2 * kotlin.math.sin(ang)).toFloat()),
                    strokeWidth = r * (if (long) 0.028f else 0.016f), cap = StrokeCap.Round
                )
            }
        }
        drawCircle(Cyan.copy(alpha = 0.35f), r * 0.80f, c, style = Stroke(r * 0.012f))
        // counter-rotating segments
        val (tl, sz) = ring(r * 0.68f)
        rotate(-rot * 1.4f, c) {
            for (k in 0 until 3) drawArc(ArcBlue, k * 120f + 8f, 90f, false, tl, sz, style = Stroke(r * 0.06f, cap = StrokeCap.Round))
        }
        // reactor triangle
        val tr = r * 0.46f
        val tri = Path().apply {
            for (k in 0 until 3) {
                val ang = Math.toRadians(-90.0 + k * 120.0)
                val x = c.x + (tr * kotlin.math.cos(ang)).toFloat(); val y = c.y + (tr * kotlin.math.sin(ang)).toFloat()
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        rotate(rot * 0.25f, c) { drawPath(tri, Cyan.copy(alpha = 0.9f), style = Stroke(r * 0.035f, join = StrokeJoin.Round)) }
        // core
        drawCircle(Brush.radialGradient(listOf(Color.White, Cyan, Color.Transparent), c, r * 0.30f * (0.9f + 0.1f * pulse)), r * 0.30f)
        drawCircle(Color.White.copy(alpha = 0.95f), r * 0.07f, c)
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
