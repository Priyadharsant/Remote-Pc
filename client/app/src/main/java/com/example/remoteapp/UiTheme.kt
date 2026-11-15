package com.example.remoteapp

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared light appearance for the desktop and Android companion apps.
object AppTheme {
    val isDark: Boolean
        get() = false

    val BackgroundDark: Color
        get() = Color(0xFFF5F7FB)

    val BackgroundSurface: Color
        get() = Color(0xFFEEF2F7)

    val CardDark: Color
        get() = Color(0xFFFFFFFF)

    val CardDarkElevated: Color
        get() = Color(0xFFFFFFFF)

    val CardDarkHighlight: Color
        get() = Color(0xFFF8FAFC)

    val CardBorder: Color
        get() = Color(0xFFE3E8F0)

    val CardBorderSubtle: Color
        get() = Color(0xFFE7EBF2)

    val CardBorderGlow: Color
        get() = Color(0x4D4F46E5)

    val AccentCyan: Color
        get() = Color(0xFF2563EB)

    val AccentSky: Color
        get() = Color(0xFF3269DF)

    val AccentPurple: Color
        get() = if (isDark) Color(0xFFA855F7) else Color(0xFF7C3AED)

    val AccentAmber: Color
        get() = Color(0xFFF59E0B)

    val AccentEmerald: Color
        get() = Color(0xFF10B981)

    val AccentRose: Color
        get() = Color(0xFFF43F5E)

    val TextPrimary: Color
        get() = Color(0xFF192439)

    val TextSecondary: Color
        get() = Color(0xFF536176)

    val TextMuted: Color
        get() = Color(0xFF78869A)

    val KeyBackground: Color
        get() = if (isDark) Color(0xFF162238) else Color(0xFFE2E8F0)

    val KeyBackgroundAccent: Color
        get() = if (isDark) Color(0xFF1E2E4A) else Color(0xFFCBD5E1)

    val KeyBorder: Color
        get() = if (isDark) Color(0x3838BDF8) else Color(0x2E0F172A)

    val CardGradient: Brush
        get() = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC)))

    val HeroCardGradient: Brush
        get() = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)))

    val CyanGradient: Brush
        get() = Brush.horizontalGradient(listOf(Color(0xFF4B7DE2), Color(0xFF3269DF)))

    val AmberGradient: Brush
        get() = Brush.horizontalGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706)))

    val DangerGradient: Brush
        get() = Brush.horizontalGradient(listOf(Color(0xFFF43F5E), Color(0xFFE11D48)))

    val colorScheme
        get() = if (isDark) {
            darkColorScheme(
                background = Color(0xFFF5F7FB),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFEEF2F7),
                primary = Color(0xFF2563EB),
                secondary = AccentAmber,
                tertiary = AccentPurple,
                onBackground = Color(0xFF192439),
                onSurface = Color(0xFF192439)
            )
        } else {
            lightColorScheme(
                background = Color(0xFFF8FAFC),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFFFFFFF),
                primary = Color(0xFF0284C7),
                secondary = AccentAmber,
                tertiary = AccentPurple,
                onBackground = Color(0xFF0F172A),
                onSurface = Color(0xFF0F172A)
            )
        }
}

fun Modifier.frostedCard(
    shape: Shape = RoundedCornerShape(16.dp),
    borderColor: Color = AppTheme.CardBorderSubtle,
    borderWidth: Dp = 1.dp,
    backgroundBrush: Brush = AppTheme.CardGradient
): Modifier = this
    .clip(shape)
    .background(backgroundBrush)
    .border(borderWidth, borderColor, shape)
