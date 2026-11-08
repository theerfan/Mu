package com.theerfan.mu.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object MuColors {
    // Backdrop: near-black, neutral greys (no colour cast).
    val bgTop = Color(0xFF121314)
    val bg = Color(0xFF050506)
    val surface = Color(0xFF151618)
    val surfaceHi = Color(0xFF222326)
    val stroke = Color(0x1AFFFFFF)
    val text = Color.White
    val textDim = Color(0xFFB8BBC2)
    val textMuted = Color(0xFF6E727B)

    // Brand / actions
    val primary = Color(0xFF3BE37A)        // the big green "play" colour
    val primaryLip = Color(0xFF1B9A4D)
    val onPrimary = Color(0xFF07301A)
    val accent = Color(0xFF2F8CFF)
    val accentLip = Color(0xFF1A5DB8)
    val pink = Color(0xFFFF5FA2)
    val cyan = Color(0xFF3DD6F5)
    val star = Color(0xFFFFC53D)
    val orange = Color(0xFFFF8A3D)

    // Judgements
    val perfect = Color(0xFF3BE37A)
    val good = Color(0xFFA6E95A)
    val ok = Color(0xFFFFC53D)
    val partial = Color(0xFFFF8A3D)
    val wrong = Color(0xFFFF4D6A)
    val miss = Color(0xFF6B6690)

    /** Lane colours, string 1 (high e) .. string 6 (low E). */
    val strings = listOf(
        Color(0xFFFF5C7A), Color(0xFFFF9F43), Color(0xFFFFD93D), Color(0xFF3BE37A), Color(0xFF3DB7FF), Color(0xFFB06BFF),
    )

    /** Gradient pairs for generated song covers and feature tiles. */
    val covers = listOf(
        Color(0xFF3BE37A) to Color(0xFF0F6B3A), Color(0xFF3DD6F5) to Color(0xFF0E4F8A), Color(0xFFFFC53D) to Color(0xFFD9541E),
        Color(0xFFFF5C7A) to Color(0xFF8A1C33), Color(0xFF2F8CFF) to Color(0xFF0B2E66), Color(0xFFFF8A3D) to Color(0xFF7A2E0B),
    )
}

private val base = Typography()
private val MuTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
)

/** Small all-caps label above sections and stats. */
val OverlineStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp, color = MuColors.textMuted)

@Composable
fun MuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MuColors.primary, onPrimary = MuColors.onPrimary, secondary = MuColors.accent,
            background = MuColors.bg, surface = MuColors.surface, surfaceVariant = MuColors.surfaceHi,
            onSurface = MuColors.text, onSurfaceVariant = MuColors.textDim, outline = MuColors.stroke,
            surfaceContainerHigh = MuColors.surface, surfaceContainerHighest = MuColors.surfaceHi,
        ),
        typography = MuTypography,
        content = content,
    )
}
