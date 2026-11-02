package com.theerfan.mu.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object MuColors {
    val bg = Color(0xFF101418)
    val surface = Color(0xFF1B2430)
    val accent = Color(0xFF4FC3F7)
    val perfect = Color(0xFF66BB6A)
    val good = Color(0xFF9CCC65)
    val ok = Color(0xFFFFCA28)
    val partial = Color(0xFFFFA726)
    val wrong = Color(0xFFEF5350)
    val miss = Color(0xFF757575)
    /** Lane colours, string 1 (high e) .. string 6 (low E). */
    val strings = listOf(Color(0xFFE57373), Color(0xFFFFB74D), Color(0xFFFFF176), Color(0xFF81C784), Color(0xFF64B5F6), Color(0xFFBA68C8))
}

@Composable
fun MuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = MuColors.accent, background = MuColors.bg, surface = MuColors.surface,
            surfaceVariant = Color(0xFF243040)),
        content = content,
    )
}
