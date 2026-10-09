package com.zetronik.torrentplayer.ui.files

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.AppSecondaryButton
import com.zetronik.torrentplayer.ui.common.DialogOption
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.OptionsDialog
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.common.toast
import com.zetronik.torrentplayer.ui.home.HomeTabBar
import com.zetronik.torrentplayer.ui.navigation.FolderRoute
import com.zetronik.torrentplayer.ui.navigation.PickerRoute
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import com.zetronik.torrentplayer.ui.theme.AppTheme

private const val PERMISSION_KEY = "permission"
private const val ADD_FILE_KEY = "add_file"

/**
 * The start screen's "Files" tab: videos, playlists and folders the user added with "Add file" /
 * "Add folder", which open the built-in browser ([PickerScreen]). No system or third-party picker.
 */
@Composable
fun FilesTab(
    tabs: @Composable () -> Unit,
    onPick: (PickerRoute) -> Unit,
    onOpenFolder: (FolderRoute) -> Unit,
    onPlay: (PlayerRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: FilesViewModel = viewModel { FilesViewModel(context.appContainer) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FilesViewModel.Event.Play -> onPlay(event.route)
                is FilesViewModel.Event.Open -> onOpenFolder(event.route)
                FilesViewModel.Event.EmptyPlaylist -> context.toast(resources.getString(R.string.files_playlist_empty))
                FilesViewModel.Event.Unavailable -> context.toast(resources.getString(R.string.files_entry_unavailable))
            }
        }
    }

    // Initial focus only helps D-pad/keyboard users; on touch it would just show a stray highlight.
    val keyboardNavigation = isKeyboardNavigation()
    val listState = rememberLazyListState()
    // Survives the trip to a folder or the player, so focus returns to the row that was opened.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocus = remember { HashMap<String, FocusRequester>() }
    var actionsFor by remember { mutableStateOf<FilesViewModel.Row?>(null) }
    val rows = state.rows

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HomeTabBar(tabs) {
            if (busy) LoadingIndicator(size = 28.dp)
            AppButton(
                onClick = { onPick(PickerRoute(folders = false)) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .focusRequester(rememberItemFocusRequester(itemFocus, ADD_FILE_KEY)),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_file_add), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.files_add_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppSecondaryButton(
                onClick = { onPick(PickerRoute(folders = true)) },
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_folder_add), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.files_add_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Added files cannot be read without it either.
            if (!state.hasPermission && !rows.isNullOrEmpty()) {
                item(key = PERMISSION_KEY) {
                    PermissionRow(
                        onResult = viewModel::refresh,
                        modifier = Modifier.focusRequester(rememberItemFocusRequester(itemFocus, PERMISSION_KEY)),
                    )
                }
            }
            when {
                rows == null -> Unit
                rows.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(R.string.files_library_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                    )
                }
                else -> itemsIndexed(rows, key = { _, row -> row.entry.path }) { index, row ->
                    val entry = row.entry
                    AppListItem(
                        selected = false,
                        position = ItemPosition.of(index, rows.size),
                        onClick = {
                            lastOpened = entry.path
                            viewModel.open(row)
                        },
                        // Long OK press on the remote, long tap on touch screens; the remote's Menu key does the same.
                        onLongClick = { actionsFor = row },
                        modifier = Modifier
                            .focusRequester(rememberItemFocusRequester(itemFocus, entry.path))
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                                    actionsFor = row
                                    true
                                } else {
                                    false
                                }
                            },
                        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        // A volume root has nothing to add under its name; an empty line would push the name up.
                        supportingContent = details(row, resources).takeIf { it.isNotEmpty() }?.let { text ->
                            { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = {
                            IconBadge(
                                kindIcon(entry.kind),
                                color = if (row.available) AppTheme.colors.accent else AppTheme.colors.tertiaryLabel,
                            )
                        },
                    )
                }
            }
        }
    }

    actionsFor?.let { row ->
        OptionsDialog(
            title = row.entry.name,
            options = listOf(
                DialogOption(stringResource(R.string.files_remove_entry), icon = R.drawable.ic_delete) { viewModel.remove(row) },
            ),
            onDismiss = { actionsFor = null },
        )
    }

    // Initial focus once the list has its rows: the row the user came back from, otherwise the first one
    // (or "Add file" while the list is empty). A newly added entry (it goes to the top) takes focus too.
    val top = rows?.firstOrNull()?.entry?.path
    var shownTop by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(rows != null, top) {
        if (rows == null) return@LaunchedEffect
        if (shownTop != null && top != shownTop) lastOpened = top
        shownTop = top
        if (!keyboardNavigation) return@LaunchedEffect
        val index = rows.indexOfFirst { it.entry.path == lastOpened }
        if (index >= 0) listState.scrollToItem(index + if (!state.hasPermission) 1 else 0)
        withFrameNanos { }
        val target = lastOpened?.let { itemFocus[it] }
            ?: itemFocus[PERMISSION_KEY]
            ?: rows.firstOrNull()?.let { itemFocus[it.entry.path] }
            ?: itemFocus[ADD_FILE_KEY]
        target?.requestFocusSafely()
    }
}

private fun details(row: FilesViewModel.Row, resources: Resources): String = buildList {
    if (!row.available) add(resources.getString(R.string.files_entry_missing))
    if (row.size > 0) add(resources.formatSize(row.size))
    if (row.location.isNotEmpty()) add(row.location)
}.joinToString(" · ")
