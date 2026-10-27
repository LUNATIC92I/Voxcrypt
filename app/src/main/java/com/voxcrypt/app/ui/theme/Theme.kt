package com.voxcrypt.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** VOXCRYPT palette: deep black, electric blue, turquoise, silver. */
object Vx {
    val Black = Color(0xFF03050A)
    val Surface = Color(0xFF070C14)
    val SurfaceHigh = Color(0xFF0C1420)
    val Line = Color(0xFF1A2940)
    val LineBright = Color(0xFF27426A)
    val Blue = Color(0xFF2A6BFF)
    val BlueDeep = Color(0xFF123A99)
    val Cyan = Color(0xFF19E3D3)
    val Silver = Color(0xFFC3CBD6)
    val SilverDim = Color(0xFF7D8898)
    val Muted = Color(0xFF4D5868)
    val Danger = Color(0xFFFF4F6D)
    val Warning = Color(0xFFFFB648)
    val Success = Color(0xFF19E3A0)

    val Mono = FontFamily.Monospace
}

private val Scheme = darkColorScheme(
    primary = Vx.Cyan,
    onPrimary = Vx.Black,
    secondary = Vx.Blue,
    onSecondary = Color.White,
    background = Vx.Black,
    onBackground = Vx.Silver,
    surface = Vx.Surface,
    onSurface = Vx.Silver,
    surfaceVariant = Vx.SurfaceHigh,
    onSurfaceVariant = Vx.SilverDim,
    outline = Vx.LineBright,
    error = Vx.Danger,
    onError = Vx.Black,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Vx.Mono, fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = 10.sp),
    headlineSmall = TextStyle(fontFamily = Vx.Mono, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = 3.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontFamily = Vx.Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 2.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Vx.Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.5.sp),
    labelSmall = TextStyle(fontFamily = Vx.Mono, fontSize = 10.sp, letterSpacing = 1.5.sp),
)

@Composable
fun VoxcryptTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
