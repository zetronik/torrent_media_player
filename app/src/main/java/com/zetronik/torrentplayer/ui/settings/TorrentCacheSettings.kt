package com.zetronik.torrentplayer.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.data.AppSettings
import com.zetronik.torrentplayer.torrent.StorageSpace
import com.zetronik.torrentplayer.torrent.StreamBudget
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.theme.AppTheme
import java.util.Locale

private const val MB = 1024L * 1024

/**
 * The "Torrent cache" section: free space, the size choice with a checkmark, and how much video the cache
 * holds.
 *  - [twoColumns] (opened full width in landscape): the choice on the left, the rest on the right, all
 *    visible at once, so the right column needs no focus.
 *  - One column: everything in one list; the storage and table cards are focusable, so on TV the D-pad
 *    can scroll down to them.
 *
 * [requestFocus] moves focus to the current choice when it appears: the section was opened, not previewed.
 */
@Composable
fun TorrentCacheSettings(
    selectedMb: Int,
    storage: StorageSpace?,
    onSelect: (Int) -> Unit,
    requestFocus: Boolean,
    twoColumns: Boolean,
    modifier: Modifier = Modifier,
) {
    val itemFocus = remember { HashMap<Int, FocusRequester>() }
    val limit = selectedMb * MB
    val warning = storage?.let { cacheWarning(limit, it) }

    if (twoColumns) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (AppTheme.layout.isTv) 32.dp else 24.dp)) {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                cacheOptions(selectedMb, onSelect, itemFocus)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (storage != null) {
                    StorageCard(storage, focusable = false, modifier = Modifier)
                    warning?.let { WarningText(it, Modifier) }
                    EstimateTable(CacheEstimate.rows(limit, storage.freeBytes), focusable = false, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    } else {
        LazyColumn(
            modifier,
            verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Always the first item, even before the space is read: an item inserted above the first visible
            // one would end up scrolled out of view, the list keeps its position by key.
            item(key = "storage") {
                if (storage != null) StorageCard(storage, focusable = true, modifier = Modifier.padding(bottom = 16.dp))
            }
            if (warning != null) {
                item(key = "warning") { WarningText(warning, Modifier.padding(bottom = 16.dp)) }
            }
            cacheOptions(selectedMb, onSelect, itemFocus)
            if (storage != null) {
                item(key = "estimate") {
                    EstimateTable(CacheEstimate.rows(limit, storage.freeBytes), focusable = true, modifier = Modifier.padding(top = 24.dp))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!requestFocus) return@LaunchedEffect
        withFrameNanos { }
        itemFocus[selectedMb]?.requestFocusSafely()
    }
}

/** The size choices and their footer. */
private fun LazyListScope.cacheOptions(selectedMb: Int, onSelect: (Int) -> Unit, itemFocus: MutableMap<Int, FocusRequester>) {
    val options = AppSettings.TORRENT_CACHE_OPTIONS
    itemsIndexed(options, key = { _, mb -> mb }) { index, mb ->
        val resources = LocalResources.current
        AppListItem(
            selected = mb == selectedMb,
            onClick = { onSelect(mb) },
            position = ItemPosition.of(index, options.size),
            modifier = Modifier.focusRequester(rememberItemFocusRequester(itemFocus, mb)),
            headlineContent = { Text(resources.formatCacheMb(mb), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (mb == AppSettings.TORRENT_CACHE_AUTO) {
                { Text(stringResource(R.string.settings_cache_auto_details), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else {
                null
            },
            trailingContent = if (mb == selectedMb) {
                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(22.dp), tint = AppTheme.colors.accent) }
            } else {
                null
            },
        )
    }
    item(key = "footer") { SectionFooter(stringResource(R.string.settings_cache_footer)) }
}

/** Why the cache will be smaller than chosen, or that there is no room at all; `null` when it fits. */
@Composable
private fun cacheWarning(limit: Long, storage: StorageSpace): String? {
    val resources = LocalResources.current
    return when {
        StreamBudget.forStream(0, storage.freeBytes) == null ->
            resources.getString(R.string.settings_cache_no_space, resources.formatSize(StreamBudget.minimumFreeBytes))
        CacheEstimate.isLimitedBySpace(limit, storage.freeBytes) ->
            resources.getString(R.string.settings_cache_limited, resources.formatSize(StreamBudget.spaceCap(storage.freeBytes)))
        else -> null
    }
}

@Composable
private fun WarningText(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.destructive,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** "Авто", or the fixed size: the value under the section's name in the section list. */
fun Resources.formatCacheMb(mb: Int): String =
    if (mb == AppSettings.TORRENT_CACHE_AUTO) getString(R.string.settings_cache_auto) else formatMb(mb)

@Composable
private fun StorageCard(storage: StorageSpace, focusable: Boolean, modifier: Modifier) {
    val resources = LocalResources.current
    val colors = AppTheme.colors
    val used = if (storage.totalBytes > 0) 1f - storage.freeBytes.toFloat() / storage.totalBytes else 0f
    Column(
        modifier
            .fillMaxWidth()
            .infoCard(focusable)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_storage_title), style = MaterialTheme.typography.titleMedium, color = colors.label)
        Text(
            stringResource(R.string.settings_storage_free, resources.formatSize(storage.freeBytes), resources.formatSize(storage.totalBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.label,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.fill),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(used.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(colors.accent),
            )
        }
        Text(
            stringResource(R.string.settings_storage_cache_cap, resources.formatSize(StreamBudget.spaceCap(storage.freeBytes).coerceAtLeast(0))),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryLabel,
        )
    }
}

/** File size × duration → minutes of video in the cache. */
@Composable
private fun EstimateTable(rows: List<CacheEstimate.Row>, focusable: Boolean, modifier: Modifier) {
    val resources = LocalResources.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.settings_estimate_title))
        Column(
            Modifier
                .fillMaxWidth()
                .infoCard(focusable)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TableRow(
                header = true,
                cells = listOf(stringResource(R.string.settings_estimate_file), stringResource(R.string.settings_estimate_cache)) +
                    CacheEstimate.DURATIONS_MINUTES.map { stringResource(R.string.settings_estimate_hours, it / 60) },
            )
            for (row in rows) {
                TableRow(
                    header = false,
                    cells = listOf(resources.formatMb((row.fileSize / MB).toInt()), row.cacheBytes?.let { resources.formatSize(it) } ?: "—") +
                        row.minutes.map { if (row.cacheBytes == null) "—" else resources.formatMinutes(it) },
                )
            }
        }
        SectionFooter(stringResource(R.string.settings_estimate_footer))
    }
}

/**
 * A read-only card. [focusable] lets it take D-pad focus, outlined while focused: in one column, without a
 * focusable item below the choices, a remote could never scroll the rest of the section into view.
 */
@Composable
private fun Modifier.infoCard(focusable: Boolean): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppTheme.shapes.card)
    return (if (focusable) focusable(interactionSource = interactions) else this)
        .then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier)
        .clip(shape)
        .background(colors.card)
}

@Composable
private fun TableRow(header: Boolean, cells: List<String>) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, text -> TableCell(text, header, first = index == 0) }
    }
}

@Composable
private fun RowScope.TableCell(text: String, header: Boolean, first: Boolean) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = if (header) AppTheme.colors.secondaryLabel else AppTheme.colors.label,
        textAlign = if (first) TextAlign.Start else TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

/** 256 МБ, 1 ГБ, 1,5 ГБ. */
private fun Resources.formatMb(mb: Int): String = when {
    mb < 1024 -> getString(R.string.unit_mb, mb.toString())
    mb % 1024 == 0 -> getString(R.string.unit_gb, (mb / 1024).toString())
    else -> getString(R.string.unit_gb, String.format(Locale.getDefault(), "%.1f", mb / 1024.0))
}

/** 12 мин, 3,3 мин: tenths only where they matter. */
private fun Resources.formatMinutes(minutes: Double): String {
    val number = if (minutes >= 10) {
        String.format(Locale.getDefault(), "%.0f", minutes)
    } else {
        String.format(Locale.getDefault(), "%.1f", minutes)
    }
    return getString(R.string.settings_estimate_minutes, number)
}
