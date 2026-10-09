package com.zetronik.torrentplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.appButtonColors
import com.zetronik.torrentplayer.ui.common.appSecondaryButtonColors
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.files.FilesTab
import com.zetronik.torrentplayer.ui.navigation.FolderRoute
import com.zetronik.torrentplayer.ui.navigation.PickerRoute
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import com.zetronik.torrentplayer.ui.recent.RecentTab
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.HomeTab
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

/** Start screen: a segmented switch between local files and recent torrents. The last tab is remembered. */
@Composable
fun HomeScreen(
    onOpenTorrent: (source: String) -> Unit,
    onOpenFolder: (FolderRoute) -> Unit,
    onPick: (PickerRoute) -> Unit,
    onPlay: (PlayerRoute) -> Unit,
) {
    val preferences = LocalContext.current.appContainer.uiPreferences
    val tab by preferences.homeTab.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(LocalScreenPadding.current),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .readableWidth()
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Each tab places the switch in its own top bar, next to its actions.
            val tabs: @Composable () -> Unit = { TabSwitch(selected = tab, onSelect = preferences::setHomeTab) }
            val content = Modifier
                .fillMaxWidth()
                .weight(1f)
            when (tab) {
                HomeTab.Files -> FilesTab(tabs = tabs, onPick = onPick, onOpenFolder = onOpenFolder, onPlay = onPlay, modifier = content)
                HomeTab.Torrents -> RecentTab(tabs = tabs, onOpenTorrent = onOpenTorrent, modifier = content)
            }
        }
    }
}

/**
 * The start screen's top bar instead of a title: the tab switch, then the tab's [actions] at the end of the
 * same line. On a phone in portrait the actions move to a line of their own.
 */
@Composable
fun HomeTabBar(tabs: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val layout = AppTheme.layout
    val spacing = if (layout.isTv) 20.dp else 12.dp
    if (layout.isCompact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { tabs() }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs()
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

/**
 * iOS-style segmented control. A tab switches on OK/tap, not on focus: the new tab moves focus into its
 * list, which would make it impossible to walk across the tabs with the D-pad.
 */
@Composable
private fun TabSwitch(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (tab in HomeTab.entries) {
            val (icon, label) = when (tab) {
                HomeTab.Files -> R.drawable.ic_folder to R.string.home_tab_files
                HomeTab.Torrents -> R.drawable.ic_movie to R.string.home_tab_torrents
            }
            // One button whose colours change, so a focused tab keeps focus when it becomes selected.
            AppButton(
                onClick = { onSelect(tab) },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                colors = if (tab == selected) appButtonColors() else appSecondaryButtonColors(),
            ) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
