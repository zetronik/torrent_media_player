package com.zetronik.torrentplayer.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Typography

/**
 * A visual template: everything that makes the app look "iOS" or "classic". Screens and components read
 * these tokens through [AppTheme] (and, for tv-material components, through the derived MaterialTheme),
 * never raw colours, so a new template is just a new [AppTemplate] value in [AppTemplates].
 */
@Immutable
data class AppTemplate(
    /** Stable key stored in preferences. */
    val id: String,
    @param:StringRes val name: Int,
    val darkColors: AppColors,
    /** Null when the template is dark-only. TV always uses [darkColors]. */
    val lightColors: AppColors?,
    val phoneShapes: AppShapes,
    val tvShapes: AppShapes,
    val phoneTypography: Typography,
    val tvTypography: Typography,
    /** List look on touch screens; TV always uses [ListStyle.Cards], which suits focus scaling. */
    val listStyle: ListStyle,
)

/** Semantic colours, named after their role (iOS naming where it fits). */
@Immutable
data class AppColors(
    val isDark: Boolean,
    /** Tint for interactive elements: filled buttons, links, checkmarks, the seek bar. */
    val accent: Color,
    val onAccent: Color,
    /** Screen background. */
    val background: Color,
    /** List rows and grouped sections on [background]. */
    val card: Color,
    /** A row under the finger. */
    val cardPressed: Color,
    /** Dialogs and sheets. */
    val elevated: Color,
    val label: Color,
    /** Details under a row title, hints. */
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    /** Hairlines between grouped rows. */
    val separator: Color,
    /** Background of secondary buttons and icon buttons. */
    val fill: Color,
    val destructive: Color,
    /** Focus highlight for D-pad / keyboard navigation (tvOS: white card, black text). */
    val focused: Color,
    val onFocused: Color,
)

/** Corner radii; components build their shapes from these. */
@Immutable
data class AppShapes(
    val button: Dp,
    val card: Dp,
    val dialog: Dp,
)

enum class ListStyle {
    /** iOS Settings look: rows joined into one rounded group, divided by inset hairlines. */
    InsetGrouped,

    /** Separate rounded rows with gaps between them. */
    Cards,
}

/** Light / dark choice for templates that have both. */
enum class ThemeMode { System, Light, Dark }
