package com.zetronik.torrentplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.DisclosureIndicator
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.ScreenHeader
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

/** Groups of the section list, in display order. A group without sections shows a "nothing yet" hint. */
enum class SettingsGroup(@StringRes val title: Int) {
    Audio(R.string.settings_group_audio),
    Video(R.string.settings_group_video),
    Torrent(R.string.settings_group_torrent),
}

/** A page of settings; a new one is an entry here plus a branch in [SectionContent] and [sectionSummary]. */
enum class SettingsSection(val group: SettingsGroup, @StringRes val title: Int, @DrawableRes val icon: Int) {
    TorrentCache(SettingsGroup.Torrent, R.string.settings_cache_section, R.drawable.ic_storage),
}

/**
 * Settings, opened from the start screen and from the player: a menu of sections grouped into "Audio",
 * "Video" and "Torrent", and the section itself.
 *  - TV and landscape windows: the menu is narrow, with a preview of the focused section beside it.
 *    → or OK opens the section: the menu hides and the section takes the whole width; ← or Back returns.
 *  - Portrait phones and tablets: the menu fills the screen and a section opens as a page of its own.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context.appContainer) }
    val layout = AppTheme.layout
    val split = layout.isTv || (layout.isLandscape && !layout.isCompact)
    // The section focused in the menu (previewed next to it), and the one opened, if any.
    var selectedName by rememberSaveable { mutableStateOf(SettingsSection.entries.first().name) }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = SettingsSection.valueOf(selectedName)
    val open = openName?.let(SettingsSection::valueOf)

    fun openSection(section: SettingsSection) {
        selectedName = section.name
        openName = section.name
    }
    val close = { openName = null }
    BackHandler(enabled = open != null, onBack = close)

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
            if (open != null) {
                ScreenHeader(title = stringResource(open.title), onBack = close)
                SectionContent(
                    open,
                    viewModel,
                    requestFocus = true,
                    wide = split,
                    modifier = Modifier
                        .fillMaxSize()
                        // Nothing in a section moves sideways, so ← always means "back to the menu".
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                close()
                                true
                            } else {
                                false
                            }
                        },
                )
                return@Column
            }
            ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)
            if (!split) {
                SectionList(
                    viewModel = viewModel,
                    selected = selected,
                    highlightSelected = false,
                    onFocusSection = {},
                    onOpenSection = ::openSection,
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (layout.isTv) 32.dp else 24.dp)) {
                SectionList(
                    viewModel = viewModel,
                    selected = selected,
                    highlightSelected = true,
                    onFocusSection = { selectedName = it.name },
                    onOpenSection = ::openSection,
                    modifier = Modifier
                        .width(if (layout.isTv) 260.dp else 240.dp)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                openSection(selected)
                                true
                            } else {
                                false
                            }
                        },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Text(
                        stringResource(selected.title),
                        style = MaterialTheme.typography.titleLarge,
                        color = AppTheme.colors.label,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    )
                    SectionContent(selected, viewModel, requestFocus = false, wide = false, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** [wide]: the section has the whole width of a landscape window and may lay out in columns. */
@Composable
private fun SectionContent(
    section: SettingsSection,
    viewModel: SettingsViewModel,
    requestFocus: Boolean,
    wide: Boolean,
    modifier: Modifier,
) {
    when (section) {
        SettingsSection.TorrentCache -> {
            val cacheMb by viewModel.torrentCacheMb.collectAsStateWithLifecycle()
            val storage by viewModel.storage.collectAsStateWithLifecycle()
            TorrentCacheSettings(cacheMb, storage, viewModel::setTorrentCacheMb, requestFocus, twoColumns = wide, modifier = modifier)
        }
    }
}

/** The current value, shown under the section's name. */
@Composable
private fun sectionSummary(section: SettingsSection, viewModel: SettingsViewModel): String {
    val resources = LocalResources.current
    return when (section) {
        SettingsSection.TorrentCache -> {
            val cacheMb by viewModel.torrentCacheMb.collectAsStateWithLifecycle()
            resources.formatCacheMb(cacheMb)
        }
    }
}

/** Sections under their group headers. [highlightSelected] marks the section shown next to the list. */
@Composable
private fun SectionList(
    viewModel: SettingsViewModel,
    selected: SettingsSection,
    highlightSelected: Boolean,
    onFocusSection: (SettingsSection) -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier,
) {
    val keyboardNavigation = isKeyboardNavigation()
    val itemFocus = remember { HashMap<SettingsSection, FocusRequester>() }

    LazyColumn(
        modifier,
        verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        SettingsGroup.entries.forEachIndexed { groupIndex, group ->
            val sections = SettingsSection.entries.filter { it.group == group }
            item(key = "group:${group.name}") {
                SectionHeader(stringResource(group.title), Modifier.padding(top = if (groupIndex > 0) 20.dp else 0.dp))
            }
            if (sections.isEmpty()) {
                item(key = "empty:${group.name}") { SectionFooter(stringResource(R.string.settings_group_empty)) }
            }
            itemsIndexed(sections, key = { _, section -> section.name }) { index, section ->
                val isSelected = highlightSelected && section == selected
                AppListItem(
                    selected = isSelected,
                    onClick = { onOpenSection(section) },
                    position = ItemPosition.of(index, sections.size),
                    modifier = Modifier
                        .focusRequester(rememberItemFocusRequester(itemFocus, section))
                        .onFocusChanged { if (it.isFocused) onFocusSection(section) },
                    headlineContent = { Text(stringResource(section.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(sectionSummary(section, viewModel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { IconBadge(section.icon) },
                    trailingContent = if (highlightSelected) null else {
                        { DisclosureIndicator() }
                    },
                    containerColor = if (isSelected) AppTheme.colors.cardPressed else AppTheme.colors.card,
                )
            }
        }
    }

    // Initial focus, also when coming back from an opened section: the section it was.
    LaunchedEffect(Unit) {
        if (!keyboardNavigation) return@LaunchedEffect
        withFrameNanos { }
        itemFocus[selected]?.requestFocusSafely()
    }
}

/** iOS grouped-list section title. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.secondaryLabel,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.secondaryLabel,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}
