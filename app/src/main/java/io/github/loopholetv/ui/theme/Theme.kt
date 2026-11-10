package io.github.loopholetv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object Tokens {
    val Background = Color(0xFF0B0C0E)
    val Surface = Color(0xFF16181B)

    /** [Surface] at 60 % over [Background]: programmes that have ended and can't be played. */
    val SurfaceDim = Color(0xFF121316)
    val SurfaceRaised = Color(0xFF24272C)
    val Outline = Color(0xFF3A3E45)
    val Accent = Color(0xFF0ABAB5)
    val AccentLight = Color(0xFF81D8D0)
    val OnBackground = Color(0xFFF4F6F7)
    val Error = Color(0xFFFF6B6B)
    val Muted = Color(0xFF9AA0A8)

    /** Focused items invert to a light pill with dark content. */
    val FocusFill = Color(0xFFF4F6F7)
    val OnFocus = Color(0xFF0B0C0E)
}

@Composable
fun LoopholeTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Tokens.Accent,
        onPrimary = Tokens.OnFocus,
        secondary = Tokens.AccentLight,
        background = Tokens.Background,
        onBackground = Tokens.OnBackground,
        surface = Tokens.Surface,
        onSurface = Tokens.OnBackground,
        surfaceVariant = Tokens.SurfaceRaised,
        onSurfaceVariant = Tokens.Muted,
        border = Tokens.Outline,
    )
    // tv-material3 defaults already meet TV minimums (title >= 20sp, body >= 14sp).
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
