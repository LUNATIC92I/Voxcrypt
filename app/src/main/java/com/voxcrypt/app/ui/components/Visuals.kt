package com.voxcrypt.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voxcrypt.app.ui.theme.Vx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * VOXCRYPT emblem: a hexagonal vault around a voice waveform, with a slowly
 * rotating segmented ring (like a lock dial).
 */
@Composable
fun VoxLogo(size: Dp = 140.dp, animated: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "ring")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(Modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        // glow
        drawCircle(Brush.radialGradient(listOf(Vx.Blue.copy(alpha = 0.25f), Color.Transparent), c, r), r)
        // segmented outer ring
        rotate(if (animated) angle else 0f, c) {
            val ringR = r * 0.92f
            for (i in 0 until 24) {
                val start = i * 15f
                drawArc(
                    color = if (i % 6 == 0) Vx.Cyan else Vx.Blue.copy(alpha = 0.7f),
                    startAngle = start, sweepAngle = if (i % 6 == 0) 10f else 6f, useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                    style = Stroke(width = r * 0.035f, cap = StrokeCap.Butt),
                )
            }
        }
        drawCircle(Vx.Line, r * 0.8f, c, style = Stroke(1f))
        // hexagon
        fun hex(radius: Float): Path = Path().apply {
            for (k in 0..5) {
                val a = (PI / 3 * k - PI / 2).toFloat()
                val p = Offset(c.x + radius * cos(a), c.y + radius * sin(a))
                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(hex(r * 0.66f), Vx.Blue, style = Stroke(r * 0.04f))
        drawPath(hex(r * 0.58f), Vx.Cyan.copy(alpha = 0.6f), style = Stroke(1.2f))
        // waveform bars
        val heights = floatArrayOf(0.28f, 0.55f, 0.85f, 0.55f, 0.28f)
        val gap = r * 0.15f
        for (i in heights.indices) {
            val wobble = if (animated) 0.85f + 0.15f * sin((pulse * 2 * PI + i).toFloat()) else 1f
            val h = r * 0.62f * heights[i] * wobble
            val x = c.x + (i - 2) * gap
            drawLine(Vx.Cyan, Offset(x, c.y - h / 2), Offset(x, c.y + h / 2), r * 0.06f, StrokeCap.Round)
        }
    }
}

/** Mirrored bar visualizer of recent input levels. */
@Composable
fun Waveform(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "idle")
    val idle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "idlephase")
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val n = levels.size
        val slot = size.width / n
        val barW = slot * 0.55f
        val mid = size.height / 2
        drawLine(Vx.Line, Offset(0f, mid), Offset(size.width, mid), 1f)
        val brush = Brush.verticalGradient(listOf(Vx.Cyan, Vx.Blue, Vx.Cyan), 0f, size.height)
        for (i in 0 until n) {
            val base = if (active) levels[i] else 0.04f + 0.04f * sin((idle * 2 * PI + i * 0.4).toFloat())
            val h = (base.coerceIn(0.02f, 1f)) * size.height * 0.92f
            val x = i * slot + (slot - barW) / 2
            val alpha = 0.35f + 0.65f * (i.toFloat() / n)
            drawRect(brush, topLeft = Offset(x, mid - h / 2), size = Size(barW, h), alpha = alpha)
        }
    }
}
