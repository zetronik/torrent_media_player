package com.zetronik.torrentplayer.ui.recent

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.data.RecentTorrent
import com.zetronik.torrentplayer.torrent.TorrentInput
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.ConfirmDialog
import com.zetronik.torrentplayer.ui.common.DialogOption
import com.zetronik.torrentplayer.ui.common.DisclosureIndicator
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.OptionsDialog
import com.zetronik.torrentplayer.ui.common.ScreenHeader
import com.zetronik.torrentplayer.ui.common.ToolbarAction
import com.zetronik.torrentplayer.ui.common.formatDate
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.common.toast
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

@Composable
fun RecentScreen(onOpenTorrent: (source: String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: RecentViewModel = viewModel { RecentViewModel(context.appContainer.recentRepository) }
    val items by viewModel.items.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    val pasteFromClipboard = {
        when (val input = context.readClipboardInput()) {
            is ClipboardResult.Found -> onOpenTorrent(input.input.source)
            ClipboardResult.Empty -> context.toast(resources.getString(R.string.clipboard_empty))
            ClipboardResult.NotTorrent -> context.toast(resources.getString(R.string.clipboard_no_torrent))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshResults.collect { result ->
            context.toast(
                if (result.updated > 0) {
                    resources.getString(R.string.recent_refresh_done, result.updated, result.requested)
                } else {
                    resources.getString(R.string.recent_refresh_failed)
                }
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(LocalScreenPadding.current),
        contentAlignment = Alignment.TopCenter,
    ) {
        val current = items
        val content = Modifier
            .readableWidth()
            .fillMaxSize()
        when {
            current == null -> Unit
            current.isEmpty() -> EmptyState(onPaste = pasteFromClipboard, modifier = content)
            else -> RecentList(
                modifier = content,
                items = current,
                refreshing = refreshing,
                onPaste = pasteFromClipboard,
                onRefreshAll = viewModel::refreshAll,
                onClearAll = { confirmClear = true },
                onOpen = { onOpenTorrent(TorrentInput.Magnet(it.magnetUri).source) },
                onRefresh = viewModel::refresh,
                onDelete = { viewModel.delete(it.infoHash) },
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.recent_clear_title),
            confirmText = stringResource(R.string.recent_clear),
            onConfirm = {
                viewModel.clearAll()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun EmptyState(onPaste: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Column(modifier) {
        ScreenHeader(stringResource(R.string.recent_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.recent_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            AppButton(
                onClick = onPaste,
                modifier = Modifier.focusRequester(focus),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.paste_from_clipboard))
            }
        }
    }
    val keyboardNavigation = isKeyboardNavigation()
    LaunchedEffect(Unit) { if (keyboardNavigation) focus.requestFocusSafely() }
}

@Composable
private fun RecentList(
    modifier: Modifier,
    items: List<RecentTorrent>,
    refreshing: Boolean,
    onPaste: () -> Unit,
    onRefreshAll: () -> Unit,
    onClearAll: () -> Unit,
    onOpen: (RecentTorrent) -> Unit,
    onRefresh: (RecentTorrent) -> Unit,
    onDelete: (RecentTorrent) -> Unit,
) {
    // Initial focus only helps D-pad/keyboard users; on touch it would just show a stray highlight.
    val keyboardNavigation = isKeyboardNavigation()
    val listState = rememberLazyListState()
    // Survives navigation to the torrent screen and back, so focus returns to the item that was opened.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocus = remember { HashMap<String, FocusRequester>() }
    val pasteFocus = remember { FocusRequester() }
    var actionsFor by remember { mutableStateOf<RecentTorrent?>(null) }
    // Where focus goes once the list settles after a dialog: the same item, or a neighbour of a deleted one.
    var pendingFocus by remember { mutableStateOf<String?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(stringResource(R.string.recent_title)) {
            ToolbarAction(
                R.drawable.ic_paste,
                stringResource(R.string.paste_from_clipboard),
                onPaste,
                Modifier.focusRequester(pasteFocus),
                prominent = true,
            )
            ToolbarAction(
                R.drawable.ic_refresh,
                stringResource(if (refreshing) R.string.recent_refreshing else R.string.recent_refresh),
                onRefreshAll,
                busy = refreshing,
            )
            ToolbarAction(R.drawable.ic_delete, stringResource(R.string.recent_clear), onClearAll)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.infoHash }) { index, torrent ->
                val focus = rememberItemFocusRequester(itemFocus, torrent.infoHash)
                RecentItem(
                    torrent = torrent,
                    position = ItemPosition.of(index, items.size),
                    onClick = {
                        lastOpened = torrent.infoHash
                        onOpen(torrent)
                    },
                    onActions = { actionsFor = torrent },
                    modifier = Modifier.focusRequester(focus),
                )
            }
        }
    }

    actionsFor?.let { torrent ->
        OptionsDialog(
            title = torrent.name,
            options = listOf(
                DialogOption(stringResource(R.string.recent_refresh_item), icon = R.drawable.ic_refresh) {
                    onRefresh(torrent)
                    pendingFocus = torrent.infoHash
                },
                DialogOption(stringResource(R.string.recent_delete_item), icon = R.drawable.ic_delete) {
                    val index = items.indexOf(torrent)
                    pendingFocus = (items.getOrNull(index + 1) ?: items.getOrNull(index - 1))?.infoHash
                    onDelete(torrent)
                },
            ),
            onDismiss = {
                if (pendingFocus == null) pendingFocus = torrent.infoHash
                actionsFor = null
            },
        )
    }

    // Initial focus: the torrent the user came back from, otherwise the first item.
    LaunchedEffect(Unit) {
        if (!keyboardNavigation) return@LaunchedEffect
        val index = items.indexOfFirst { it.infoHash == lastOpened }.takeIf { it >= 0 } ?: 0
        listState.scrollToItem(index)
        withFrameNanos { }
        itemFocus[items[index].infoHash]?.requestFocusSafely() ?: pasteFocus.requestFocusSafely()
    }

    // After the actions dialog: keep focus in the list instead of letting it fall off a removed item.
    LaunchedEffect(pendingFocus, items) {
        val key = pendingFocus ?: return@LaunchedEffect
        if (actionsFor != null) return@LaunchedEffect
        withFrameNanos { }
        if (keyboardNavigation) itemFocus[key]?.requestFocusSafely() ?: pasteFocus.requestFocusSafely()
        pendingFocus = null
    }
}

@Composable
private fun RecentItem(
    torrent: RecentTorrent,
    position: ItemPosition,
    onClick: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    AppListItem(
        selected = false,
        onClick = onClick,
        position = position,
        // Long OK press on the remote, long tap on touch screens; the remote's Menu key does the same.
        onLongClick = onActions,
        modifier = modifier.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                onActions()
                true
            } else {
                false
            }
        },
        headlineContent = { Text(torrent.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.recent_item_details,
                    resources.formatSize(torrent.totalSize),
                    torrent.seeds,
                    torrent.peers,
                    formatDate(torrent.lastOpenedAt),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { IconBadge(R.drawable.ic_movie) },
        trailingContent = { DisclosureIndicator() },
    )
}

private sealed interface ClipboardResult {
    data class Found(val input: TorrentInput) : ClipboardResult
    data object Empty : ClipboardResult
    data object NotTorrent : ClipboardResult
}

private fun Context.readClipboardInput(): ClipboardResult {
    val clipboard = getSystemService(ClipboardManager::class.java)
    val text = clipboard?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
    if (text.isNullOrBlank()) return ClipboardResult.Empty
    // A path to a local file must not be accepted from the clipboard.
    val input = TorrentInput.parse(text)?.takeIf { it !is TorrentInput.TorrentFile }
    return input?.let { ClipboardResult.Found(it) } ?: ClipboardResult.NotTorrent
}
