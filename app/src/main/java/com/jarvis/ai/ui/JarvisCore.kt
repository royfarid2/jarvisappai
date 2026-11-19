package com.jarvis.ai.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CoreState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * The arc-reactor style core. The state comes from the real assistant (recogniser / AI request / speech / error).
 * LISTENING follows the real microphone level. SPEAKING is a steady pulse: the TTS engine does not expose its audio
 * amplitude, so it is not synchronised with the waveform of the voice.
 */
@Composable
fun JarvisCore(state: CoreState, level: Float, modifier: Modifier = Modifier, coreSize: Dp = 150.dp) {
    val t = rememberInfiniteTransition(label = "core")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == CoreState.THINKING) 1400 else 12000, easing = LinearEasing)), label = "spin")
    val spinBack by t.animateFloat(360f, 0f, infiniteRepeatable(tween(if (state == CoreState.THINKING) 2200 else 18000, easing = LinearEasing)), label = "spinBack")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == CoreState.SPEAKING) 700 else 2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "level")
    val color = if (state == CoreState.ERROR) Color(0xFFFF5C5C) else Cyan

    Canvas(modifier.size(coreSize)) {
        val c = center
        val r = size.minDimension / 2f
        val amp = when (state) {
            CoreState.LISTENING -> 0.05f + 0.30f * lvl
            CoreState.SPEAKING -> 0.08f + 0.14f * pulse
            CoreState.THINKING -> 0.04f
            CoreState.IDLE -> 0.02f + 0.03f * pulse
            CoreState.ERROR -> 0f
        }
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.40f + 0.25f * amp), Color.Transparent), c, r), radius = r, center = c)
        val ringW = 2.5.dp.toPx()
        val outer = r * 0.88f
        drawArc(color.copy(alpha = 0.85f), spin, if (state == CoreState.THINKING) 110f else 250f, false,
            Offset(c.x - outer, c.y - outer), Size(outer * 2, outer * 2), style = Stroke(ringW, cap = StrokeCap.Round))
        val mid = r * 0.70f
        drawArc(ArcBlue.copy(alpha = 0.8f), spinBack, if (state == CoreState.THINKING) 80f else 200f, false,
            Offset(c.x - mid, c.y - mid), Size(mid * 2, mid * 2), style = Stroke(ringW * 0.8f, cap = StrokeCap.Round))
        drawCircle(color.copy(alpha = 0.35f), radius = r * (0.52f + amp * 0.6f), center = c, style = Stroke(1.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0.2f)), c, r * (0.40f + amp)),
            radius = r * (0.30f + amp), center = c)
    }
}
