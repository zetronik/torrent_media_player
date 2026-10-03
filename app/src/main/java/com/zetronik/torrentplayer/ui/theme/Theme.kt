package com.zetronik.torrentplayer.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val ColorScheme = darkColorScheme(
    primary = Color(0xFF3D7BFF),
    onPrimary = Color.White,
    secondary = Color(0xFF9DB4E8),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF1B1D23),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF2A2D35),
    onSurfaceVariant = Color(0xFFB4B8C2),
    error = Color(0xFFFF6B6B),
)

/** Padding that keeps content inside the TV overscan-safe area; much smaller on phones. */
val LocalScreenPadding = staticCompositionLocalOf { PaddingValues(16.dp) }

/** True on a 10-foot UI (TV), where navigation is D-pad first. */
val LocalIsTv = staticCompositionLocalOf { false }

@Composable
fun TorrentPlayerTheme(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val padding = if (isTv) PaddingValues(horizontal = 48.dp, vertical = 27.dp) else PaddingValues(16.dp)
    MaterialTheme(colorScheme = ColorScheme) {
        CompositionLocalProvider(
            LocalScreenPadding provides padding,
            LocalIsTv provides isTv,
            // Text outside a Surface would otherwise fall back to black on the dark background.
            LocalContentColor provides ColorScheme.onBackground,
            content = content,
        )
    }
}
