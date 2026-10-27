package com.voxcrypt.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxcrypt.app.ui.theme.Vx

/** Background: deep black, faint grid and a cold glow at the top. */
fun Modifier.vxBackground(): Modifier = this
    .background(Vx.Black)
    .drawBehind {
        val step = 32.dp.toPx()
        val gridColor = Vx.Line.copy(alpha = 0.25f)
        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.6f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.6f)
            y += step
        }
        drawRect(
            Brush.radialGradient(
                listOf(Vx.Blue.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 0.9f,
            ),
        )
    }

/** Screen frame: title bar, scrollable content limited in width on tablets. */
@Composable
fun VxScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    showHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().vxBackground().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().widthIn(max = 720.dp).align(Alignment.TopCenter),
        ) {
            if (showHeader) Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        Modifier.size(44.dp).clip(CutCornerShape(6.dp))
                            .clickable(role = Role.Button, onClickLabel = "Retour", onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(VxIcons.Back, contentDescription = "Retour", tint = Vx.Cyan, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Vx.Silver, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Vx.SilverDim)
                    }
                }
            }
            if (showHeader) ScanLine()
            val body = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
            Column(
                if (scrollable) body.verticalScroll(rememberScrollState()) else body,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Thin animated line under the title bar. */
@Composable
fun ScanLine(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "scan")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "scanpos")
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(Vx.Line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val x = p * size.width
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Vx.Cyan, Color.Transparent), x - 80f, x + 80f),
            Offset(x - 80f, size.height / 2), Offset(x + 80f, size.height / 2), 2f,
        )
    }
}

private fun DrawScope.cornerBrackets(color: Color, len: Float, stroke: Float) {
    val w = size.width
    val h = size.height
    val s = stroke / 2
    drawLine(color, Offset(s, s), Offset(len, s), stroke, StrokeCap.Square)
    drawLine(color, Offset(s, s), Offset(s, len), stroke, StrokeCap.Square)
    drawLine(color, Offset(w - s, s), Offset(w - len, s), stroke, StrokeCap.Square)
    drawLine(color, Offset(w - s, s), Offset(w - s, len), stroke, StrokeCap.Square)
    drawLine(color, Offset(s, h - s), Offset(len, h - s), stroke, StrokeCap.Square)
    drawLine(color, Offset(s, h - s), Offset(s, h - len), stroke, StrokeCap.Square)
    drawLine(color, Offset(w - s, h - s), Offset(w - len, h - s), stroke, StrokeCap.Square)
    drawLine(color, Offset(w - s, h - s), Offset(w - s, h - len), stroke, StrokeCap.Square)
}

/** Panel with a hairline border and bright corner brackets. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = Vx.Cyan,
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Vx.SurfaceHigh, Vx.Surface)))
            .border(1.dp, Vx.Line)
            .drawBehind { cornerBrackets(accent.copy(alpha = 0.85f), 12.dp.toPx(), 1.5.dp.toPx()) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (label != null) SectionLabel(label, accent)
        content()
    }
}

@Composable
fun SectionLabel(text: String, color: Color = Vx.Cyan) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

enum class ButtonKind { Primary, Secondary, Danger }

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.Primary,
    height: Dp = 50.dp,
) {
    val accent = when (kind) {
        ButtonKind.Primary -> Vx.Cyan
        ButtonKind.Secondary -> Vx.Blue
        ButtonKind.Danger -> Vx.Danger
    }
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val fill = when (kind) {
        ButtonKind.Primary -> Brush.horizontalGradient(listOf(Vx.BlueDeep.copy(alpha = 0.55f), Vx.Cyan.copy(alpha = 0.22f)))
        ButtonKind.Secondary -> Brush.horizontalGradient(listOf(Vx.SurfaceHigh, Vx.Blue.copy(alpha = 0.12f)))
        ButtonKind.Danger -> Brush.horizontalGradient(listOf(Vx.SurfaceHigh, Vx.Danger.copy(alpha = 0.14f)))
    }
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .background(fill)
            .border(1.dp, accent.copy(alpha = 0.8f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Vx.Silver, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Compact icon + label action used in lists. */
@Composable
fun ActionChip(text: String, icon: ImageVector, onClick: () -> Unit, color: Color = Vx.Cyan, enabled: Boolean = true) {
    val shape = CutCornerShape(5.dp)
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Vx.Silver)
    }
}

/** Home menu entry. */
@Composable
fun MenuTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, accent: Color = Vx.Cyan) {
    val shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Vx.SurfaceHigh, Vx.Surface)))
            .border(1.dp, Vx.Line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).border(1.dp, accent.copy(alpha = 0.6f), CutCornerShape(8.dp))
                .background(accent.copy(alpha = 0.08f), CutCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Vx.Silver)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim)
        }
        Text("›", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

/** Label / value line with a status dot. */
@Composable
fun StatusLine(label: String, value: String, color: Color = Vx.Cyan) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Vx.SilverDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), color = color)
    }
}

/** Key / value line for technical details. */
@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Vx.SilverDim, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Vx.Mono), color = Vx.Silver, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), CutCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Progress bar: determinate when [fraction] is not null, sweeping otherwise. */
@Composable
fun VxProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "progress")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val animated by animateFloatAsState(fraction ?: 0f, label = "fraction")
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        drawRect(Vx.Line)
        val brush = Brush.horizontalGradient(listOf(Vx.Blue, Vx.Cyan))
        if (fraction != null) {
            drawRect(brush, size = size.copy(width = size.width * animated))
        } else {
            val w = size.width * 0.3f
            val x = -w + sweep * (size.width + w)
            drawRect(brush, topLeft = Offset(x, 0f), size = size.copy(width = w))
        }
        // segment ticks
        val ticks = 20
        for (i in 1 until ticks) {
            val x = size.width * i / ticks
            drawLine(Vx.Black, Offset(x, 0f), Offset(x, size.height), 2f)
        }
    }
}

/** Inline message block (info, warning or error). */
@Composable
fun Notice(text: String, color: Color = Vx.SilverDim, icon: ImageVector = VxIcons.Info) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.07f)).border(1.dp, color.copy(alpha = 0.35f)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Vx.Silver)
    }
}
