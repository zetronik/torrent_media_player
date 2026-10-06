package com.zetronik.torrentplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.zetronik.torrentplayer.R

/** All templates the user can pick from. The first one is the default. */
object AppTemplates {
    val all: List<AppTemplate> by lazy { listOf(Ios, Classic) }

    val default: AppTemplate get() = all.first()

    fun byId(id: String?): AppTemplate = all.firstOrNull { it.id == id } ?: default
}

/**
 * iOS / tvOS: system colours from Apple's HIG, black grouped background in dark mode, inset grouped lists
 * on touch screens, white focus cards on TV.
 */
private val Ios = AppTemplate(
    id = "ios",
    name = R.string.template_ios,
    darkColors = AppColors(
        isDark = true,
        accent = Color(0xFF0A84FF),
        onAccent = Color.White,
        background = Color(0xFF000000),
        card = Color(0xFF1C1C1E),
        cardPressed = Color(0xFF3A3A3C),
        elevated = Color(0xFF2C2C2E),
        label = Color.White,
        secondaryLabel = Color(0x99EBEBF5),
        tertiaryLabel = Color(0x4DEBEBF5),
        separator = Color(0xA6545458),
        fill = Color(0x3D767680),
        destructive = Color(0xFFFF453A),
        focused = Color.White,
        onFocused = Color.Black,
    ),
    lightColors = AppColors(
        isDark = false,
        accent = Color(0xFF007AFF),
        onAccent = Color.White,
        background = Color(0xFFF2F2F7),
        card = Color.White,
        cardPressed = Color(0xFFD1D1D6),
        elevated = Color.White,
        label = Color.Black,
        secondaryLabel = Color(0x993C3C43),
        tertiaryLabel = Color(0x4D3C3C43),
        separator = Color(0x4A3C3C43),
        fill = Color(0x1F767680),
        destructive = Color(0xFFFF3B30),
        focused = Color(0xFF1C1C1E),
        onFocused = Color.White,
    ),
    phoneShapes = AppShapes(button = 12.dp, card = 12.dp, dialog = 16.dp),
    tvShapes = AppShapes(button = 14.dp, card = 14.dp, dialog = 24.dp),
    phoneTypography = iosTypography(scale = 1f),
    // TV dp are larger on screen than phone dp at viewing distance, so the text sizes are trimmed slightly.
    tvTypography = iosTypography(scale = 0.9f),
    listStyle = ListStyle.InsetGrouped,
)

/** The original look: blue accent on a dark grey background, separate list cards, Material type scale. */
private val Classic = AppTemplate(
    id = "classic",
    name = R.string.template_classic,
    darkColors = AppColors(
        isDark = true,
        accent = Color(0xFF3D7BFF),
        onAccent = Color.White,
        background = Color(0xFF101114),
        card = Color(0xFF1B1D23),
        cardPressed = Color(0xFF2A2D35),
        elevated = Color(0xFF1B1D23),
        label = Color(0xFFE6E7EB),
        secondaryLabel = Color(0xFFB4B8C2),
        tertiaryLabel = Color(0xFF7A7E88),
        separator = Color(0xFF2A2D35),
        fill = Color(0xCC2A2D35),
        destructive = Color(0xFFFF6B6B),
        focused = Color(0xFFE6E7EB),
        onFocused = Color(0xFF101114),
    ),
    lightColors = null,
    phoneShapes = AppShapes(button = 20.dp, card = 12.dp, dialog = 16.dp),
    tvShapes = AppShapes(button = 20.dp, card = 12.dp, dialog = 16.dp),
    phoneTypography = Typography(),
    tvTypography = Typography(),
    listStyle = ListStyle.Cards,
)

/**
 * Apple's text styles mapped onto the Material slots the components use:
 * headlineMedium = Large Title, headlineSmall = Title 2, titleLarge = Title 3, titleMedium = Headline,
 * bodyLarge = Body, bodyMedium = Subheadline, bodySmall = Footnote, labelLarge = button text.
 */
private fun iosTypography(scale: Float): Typography {
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
        fontSize = (size * scale).sp,
        lineHeight = (line * scale).sp,
        fontWeight = weight,
        letterSpacing = tracking.em,
    )
    return Typography(
        displayLarge = style(56, 64, FontWeight.Bold, -0.01),
        displayMedium = style(44, 52, FontWeight.Bold, -0.01),
        displaySmall = style(36, 44, FontWeight.Bold, -0.01),
        headlineLarge = style(34, 41, FontWeight.Bold, 0.01),
        headlineMedium = style(34, 41, FontWeight.Bold, 0.01),
        headlineSmall = style(22, 28, FontWeight.Bold, -0.02),
        titleLarge = style(20, 25, FontWeight.SemiBold, -0.02),
        titleMedium = style(17, 22, FontWeight.SemiBold, -0.02),
        titleSmall = style(15, 20, FontWeight.SemiBold, -0.01),
        bodyLarge = style(17, 22, tracking = -0.02),
        bodyMedium = style(15, 20, tracking = -0.01),
        bodySmall = style(13, 18),
        labelLarge = style(17, 22, FontWeight.SemiBold, -0.02),
        labelMedium = style(13, 18, FontWeight.Medium),
        labelSmall = style(12, 16, FontWeight.Medium),
    )
}
