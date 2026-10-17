package com.example.remoteapp

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Observable state for Theme (Dark vs Light with Auto-Detection)
object ThemeState {
    var isDark by mutableStateOf(false)
    var isAutoTheme by mutableStateOf(true)
}

// Adaptive Theme Supporting Both Black (Dark) and White (Light) Neumorphic Aesthetics
object AppTheme {
    val isDark: Boolean
        get() = ThemeState.isDark

    val BackgroundDark: Color
        get() = if (isDark) Color(0xFF0B0F19) else Color(0xFFEDF2F7)

    val BackgroundSurface: Color
        get() = if (isDark) Color(0xFF111827) else Color(0xFFE2E8F0)

    val CardDark: Color
        get() = if (isDark) Color(0xFF161F30) else Color(0xFFFFFFFF)

    val CardDarkElevated: Color
        get() = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF)

    val CardDarkHighlight: Color
        get() = if (isDark) Color(0xFF26354D) else Color(0xFFF8FAFC)

    val CardBorder: Color
        get() = if (isDark) Color(0x3338BDF8) else Color(0x14000000)

    val CardBorderSubtle: Color
        get() = if (isDark) Color(0x24FFFFFF) else Color(0x0F000000)

    val CardBorderGlow: Color
        get() = if (isDark) Color(0x4000E5FF) else Color(0x334F46E5)

    val AccentCyan: Color
        get() = if (isDark) Color(0xFF00E5FF) else Color(0xFF4F46E5)

    val AccentSky: Color
        get() = if (isDark) Color(0xFF38BDF8) else Color(0xFF3B82F6)

    val AccentPurple: Color
        get() = if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1)

    val AccentAmber: Color
        get() = Color(0xFFF59E0B)

    val AccentEmerald: Color
        get() = Color(0xFF10B981)

    val AccentRose: Color
        get() = Color(0xFFEF4444)

    val TextPrimary: Color
        get() = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)

    val TextSecondary: Color
        get() = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)

    val TextMuted: Color
        get() = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)

    val KeyBackground: Color
        get() = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)

    val KeyBackgroundAccent: Color
        get() = if (isDark) Color(0xFF26354D) else Color(0xFFCBD5E1)

    val KeyBorder: Color
        get() = if (isDark) Color(0x3338BDF8) else Color(0x2E0F172A)

    val CardGradient: Brush
        get() = if (isDark) {
            Brush.verticalGradient(listOf(Color(0xFF161F30), Color(0xFF111827)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC)))
        }

    val HeroCardGradient: Brush
        get() = if (isDark) {
            Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
        } else {
            Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)))
        }

    val CyanGradient: Brush
        get() = if (isDark) {
            Brush.horizontalGradient(listOf(Color(0xFF00B4D8), Color(0xFF00E5FF)))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF4F46E5)))
        }

    val AmberGradient: Brush
        get() = Brush.horizontalGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706)))

    val DangerGradient: Brush
        get() = Brush.horizontalGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))

    val colorScheme
        get() = if (isDark) {
            darkColorScheme(
                background = Color(0xFF0B0F19),
                surface = Color(0xFF161F30),
                surfaceVariant = Color(0xFF1E293B),
                primary = Color(0xFF00E5FF),
                secondary = AccentAmber,
                tertiary = AccentPurple,
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC)
            )
        } else {
            lightColorScheme(
                background = Color(0xFFEDF2F7),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFFFFFFF),
                primary = Color(0xFF4F46E5),
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
