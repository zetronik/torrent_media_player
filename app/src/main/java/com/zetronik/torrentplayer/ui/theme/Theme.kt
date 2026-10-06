package com.zetronik.torrentplayer.ui.theme

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * Window size facts the screens adapt to. Derived from the current window (not the physical screen), so
 * split-screen and freeform windows get the compact layouts too.
 */
@Immutable
data class AppLayout(val isTv: Boolean, val widthDp: Int, val heightDp: Int) {
    val isLandscape: Boolean get() = widthDp > heightDp

    /** Phone portrait or a narrow window: toolbars collapse to icons, button rows stack. */
    val isCompact: Boolean get() = !isTv && widthDp < 600

    /** Phone landscape: little vertical room, so headers go inline and paddings shrink. */
    val isShort: Boolean get() = !isTv && heightDp < 480

    /** Large touch screens, where content is kept to a readable width instead of spanning the window. */
    val isExpanded: Boolean get() = !isTv && widthDp >= 840
}

/** Padding that keeps content inside the TV overscan-safe area; much smaller on phones. */
val LocalScreenPadding = staticCompositionLocalOf { PaddingValues(16.dp) }

/** True on a 10-foot UI (TV), where navigation is D-pad first. */
val LocalIsTv = staticCompositionLocalOf { false }

val LocalAppLayout = staticCompositionLocalOf { AppLayout(isTv = false, widthDp = 360, heightDp = 640) }

private val LocalAppColors = staticCompositionLocalOf<AppColors> { error("No AppTheme") }
private val LocalAppShapes = staticCompositionLocalOf<AppShapes> { error("No AppTheme") }
private val LocalListStyle = staticCompositionLocalOf { ListStyle.Cards }

/** Accessors for the current template's tokens. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val shapes: AppShapes
        @Composable @ReadOnlyComposable get() = LocalAppShapes.current
    val listStyle: ListStyle
        @Composable @ReadOnlyComposable get() = LocalListStyle.current
    val layout: AppLayout
        @Composable @ReadOnlyComposable get() = LocalAppLayout.current
}

@Composable
fun TorrentPlayerTheme(preferences: UiPreferences, content: @Composable () -> Unit) {
    val template by preferences.template.collectAsStateWithLifecycle()
    val themeMode by preferences.themeMode.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val systemDark = isSystemInDarkTheme()
    val light = template.lightColors?.takeIf {
        !isTv && when (themeMode) {
            ThemeMode.System -> !systemDark
            ThemeMode.Light -> true
            ThemeMode.Dark -> false
        }
    }
    val colors = light ?: template.darkColors
    val shapes = if (isTv) template.tvShapes else template.phoneShapes
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val layout = with(density) {
        AppLayout(isTv, windowSize.width.toDp().value.toInt(), windowSize.height.toDp().value.toInt())
    }
    val padding = when {
        isTv -> PaddingValues(horizontal = 48.dp, vertical = 27.dp)
        layout.isShort -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        layout.isCompact -> PaddingValues(16.dp)
        else -> PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    }
    val colorScheme = remember(colors) { colors.toColorScheme() }
    val materialShapes = remember(shapes) {
        Shapes(
            extraSmall = RoundedCornerShape(shapes.card / 3),
            small = RoundedCornerShape(shapes.card / 2),
            medium = RoundedCornerShape(shapes.card),
            large = RoundedCornerShape(shapes.dialog),
            extraLarge = RoundedCornerShape(shapes.dialog * 1.5f),
        )
    }

    SystemBarsAppearance(darkContent = !colors.isDark)
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = materialShapes,
        typography = if (isTv) template.tvTypography else template.phoneTypography,
    ) {
        CompositionLocalProvider(
            LocalAppColors provides colors,
            LocalAppShapes provides shapes,
            LocalListStyle provides if (isTv) ListStyle.Cards else template.listStyle,
            LocalAppLayout provides layout,
            LocalScreenPadding provides padding,
            LocalIsTv provides isTv,
            // Text outside a Surface would otherwise fall back to black on the dark background.
            LocalContentColor provides colors.label,
            content = content,
        )
    }
}

/** Dark status / navigation bar icons on a light template, light icons on a dark one. */
@Composable
private fun SystemBarsAppearance(darkContent: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkContent
            isAppearanceLightNavigationBars = darkContent
        }
    }
}

/** tv-material components read MaterialTheme colours; they are derived from the template's roles. */
private fun AppColors.toColorScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        secondaryContainer = fill, onSecondaryContainer = label,
        background = background, onBackground = label, surface = card, onSurface = label,
        surfaceVariant = fill, onSurfaceVariant = secondaryLabel, surfaceTint = card,
        inverseSurface = focused, inverseOnSurface = onFocused,
        error = destructive, onError = onAccent, border = separator, borderVariant = separator,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        secondaryContainer = fill, onSecondaryContainer = label,
        background = background, onBackground = label, surface = card, onSurface = label,
        surfaceVariant = fill, onSurfaceVariant = secondaryLabel, surfaceTint = card,
        inverseSurface = focused, inverseOnSurface = onFocused,
        error = destructive, onError = onAccent, border = separator, borderVariant = separator,
    )
}
