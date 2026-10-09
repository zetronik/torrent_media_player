package com.zetronik.torrentplayer.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.media.FolderItem
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.DisclosureIndicator
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.ScreenHeader
import com.zetronik.torrentplayer.ui.common.ToolbarAction
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

private const val PERMISSION_KEY = "permission"

/**
 * Built-in file browser for "Add file" / "Add folder": starts at the volumes (internal storage, then any
 * attached drives) and walks into folders. Only videos, playlists and folders are listed.
 */
@Composable
fun PickerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: PickerViewModel = viewModel { PickerViewModel(createSavedStateHandle(), context.appContainer) }
    val levels by viewModel.levels.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val goBack = { if (!viewModel.up()) onClose() }

    LaunchedEffect(done) { if (done) onClose() }
    LifecycleResumeEffect(viewModel) {
        viewModel.reload()
        onPauseOrDispose { }
    }
    BackHandler(enabled = levels.isNotEmpty()) { viewModel.up() }

    val keyboardNavigation = isKeyboardNavigation()
    val listState = rememberLazyListState()
    val itemFocus = remember { HashMap<String, FocusRequester>() }
    val current = levels.lastOrNull()

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
            ScreenHeader(
                title = current?.name ?: stringResource(if (viewModel.pickFolders) R.string.picker_folder_title else R.string.picker_file_title),
                // The way here, without the current folder (it is the title).
                subtitle = levels.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(" › ") { it.name },
                largeTitle = false,
                onBack = goBack,
                actions = if (viewModel.pickFolders && current != null) {
                    {
                        ToolbarAction(
                            R.drawable.ic_check,
                            stringResource(R.string.picker_choose_folder),
                            viewModel::pickCurrent,
                            prominent = true,
                        )
                    }
                } else {
                    null
                },
            )
            when (val shown = state) {
                PickerViewModel.State.Loading -> Unit
                PickerViewModel.State.Unavailable -> Hint(stringResource(R.string.files_folder_unavailable))
                is PickerViewModel.State.Volumes -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (!shown.hasPermission) {
                        item(key = PERMISSION_KEY) {
                            PermissionRow(
                                onResult = viewModel::reload,
                                modifier = Modifier.focusRequester(rememberItemFocusRequester(itemFocus, PERMISSION_KEY)),
                            )
                        }
                    }
                    itemsIndexed(shown.volumes, key = { _, volume -> volume.path }) { index, volume ->
                        AppListItem(
                            selected = false,
                            position = ItemPosition.of(index, shown.volumes.size),
                            onClick = { viewModel.openVolume(volume) },
                            modifier = Modifier.focusRequester(rememberItemFocusRequester(itemFocus, volume.path)),
                            headlineContent = { Text(volume.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.files_volume_space,
                                        resources.formatSize(volume.freeBytes),
                                        resources.formatSize(volume.totalBytes),
                                    )
                                )
                            },
                            leadingContent = { IconBadge(if (volume.removable) R.drawable.ic_usb else R.drawable.ic_storage) },
                            trailingContent = { DisclosureIndicator() },
                        )
                    }
                }
                is PickerViewModel.State.Folder ->
                    if (shown.items.isEmpty()) {
                        Hint(stringResource(if (viewModel.pickFolders) R.string.picker_no_subfolders else R.string.files_folder_empty))
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            itemsIndexed(shown.items, key = { _, item -> item.path }) { index, item ->
                                FolderItemRow(
                                    item = item,
                                    position = ItemPosition.of(index, shown.items.size),
                                    onClick = {
                                        when (item) {
                                            is FolderItem.Folder -> viewModel.openFolder(item)
                                            is FolderItem.File -> viewModel.pick(item)
                                        }
                                    },
                                    modifier = Modifier.focusRequester(rememberItemFocusRequester(itemFocus, item.path)),
                                )
                            }
                        }
                    }
            }
        }
    }

    // After every move: focus the folder the user came up from, otherwise the first row.
    LaunchedEffect(state) {
        val keys = when (val shown = state) {
            is PickerViewModel.State.Volumes -> listOfNotNull(PERMISSION_KEY.takeIf { !shown.hasPermission }) + shown.volumes.map { it.path }
            is PickerViewModel.State.Folder -> shown.items.map { it.path }
            else -> return@LaunchedEffect
        }
        if (keys.isEmpty()) return@LaunchedEffect
        val index = keys.indexOf(viewModel.focusPath).takeIf { it >= 0 } ?: 0
        listState.scrollToItem(index)
        if (!keyboardNavigation) return@LaunchedEffect
        withFrameNanos { }
        itemFocus[keys[index]]?.requestFocusSafely()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = AppTheme.colors.secondaryLabel,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
    )
}
