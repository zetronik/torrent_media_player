package com.zetronik.torrentplayer.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tabs of the start screen. */
enum class HomeTab { Files, Torrents }

/** Look-and-feel choices; the theme follows these flows, so a change applies without a restart. */
class UiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _template = MutableStateFlow(AppTemplates.byId(prefs.getString(KEY_TEMPLATE, null)))
    val template: StateFlow<AppTemplate> = _template.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME_MODE, null) } ?: ThemeMode.System
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** The start screen's last open tab, restored on the next launch. */
    private val _homeTab = MutableStateFlow(
        HomeTab.entries.firstOrNull { it.name == prefs.getString(KEY_HOME_TAB, null) } ?: HomeTab.Files
    )
    val homeTab: StateFlow<HomeTab> = _homeTab.asStateFlow()

    fun setHomeTab(tab: HomeTab) {
        prefs.edit { putString(KEY_HOME_TAB, tab.name) }
        _homeTab.value = tab
    }

    fun setTemplate(template: AppTemplate) {
        prefs.edit { putString(KEY_TEMPLATE, template.id) }
        _template.value = template
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _themeMode.value = mode
    }

    private companion object {
        const val PREFS_NAME = "ui"
        const val KEY_TEMPLATE = "template"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_HOME_TAB = "home_tab"
    }
}
