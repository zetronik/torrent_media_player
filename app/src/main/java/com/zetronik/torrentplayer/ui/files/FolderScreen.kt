package com.zetronik.torrentplayer.ui.files

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.zetronik.torrentplayer.data.PlaybackPosition
import com.zetronik.torrentplayer.media.FolderItem
import com.zetronik.torrentplayer.media.LocalKind
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.DisclosureIndicator
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.ScreenHeader
import com.zetronik.torrentplayer.ui.common.formatDuration
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.common.toast
import com.zetronik.torrentplayer.ui.navigation.FolderRoute
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

/** A local folder: subfolders first, then videos and playlists. */
@Composable
fun FolderScreen(
    onOpenFolder: (FolderRoute) -> Unit,
    onPlay: (PlayerRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: FolderViewModel = viewModel { FolderViewModel(createSavedStateHandle(), context.appContainer) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.reload()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FolderViewModel.Event.Play -> onPlay(event.route)
                FolderViewModel.Event.EmptyPlaylist -> context.toast(resources.getString(R.string.files_playlist_empty))
            }
        }
    }

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
                title = viewModel.title,
                largeTitle = false,
                onBack = onBack,
                actions = if (busy) {
                    { LoadingIndicator(size = 28.dp) }
                } else {
                    null
                },
            )
            when (val current = state) {
                FolderViewModel.State.Loading -> Unit
                FolderViewModel.State.Unavailable -> Message(stringResource(R.string.files_folder_unavailable), onBack)
                is FolderViewModel.State.Ready ->
                    if (current.items.isEmpty()) {
                        Message(stringResource(R.string.files_folder_empty), onBack)
                    } else {
                        FolderList(
                            items = current.items,
                            positions = positions,
                            onOpenFolder = { onOpenFolder(FolderRoute(it.path, it.name)) },
                            onOpenFile = viewModel::open,
                        )
                    }
            }
        }
    }
}

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        AppButton(onClick = onBack, modifier = Modifier.focusRequester(focus)) {
            Text(stringResource(R.string.back))
        }
    }
    val keyboardNavigation = isKeyboardNavigation()
    LaunchedEffect(Unit) { if (keyboardNavigation) focus.requestFocusSafely() }
}

@Composable
private fun FolderList(
    items: List<FolderItem>,
    positions: Map<String, PlaybackPosition>,
    onOpenFolder: (FolderItem.Folder) -> Unit,
    onOpenFile: (FolderItem.File) -> Unit,
) {
    val keyboardNavigation = isKeyboardNavigation()
    val listState = rememberLazyListState()
    // Survives the trip to a subfolder or the player, so focus returns to the row that was opened.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocus = remember { HashMap<String, FocusRequester>() }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val focus = rememberItemFocusRequester(itemFocus, item.key)
            FolderItemRow(
                item = item,
                position = ItemPosition.of(index, items.size),
                onClick = {
                    lastOpened = item.key
                    when (item) {
                        is FolderItem.Folder -> onOpenFolder(item)
                        is FolderItem.File -> onOpenFile(item)
                    }
                },
                modifier = Modifier.focusRequester(focus),
                resume = (item as? FolderItem.File)?.let { positions[it.uri] },
            )
        }
    }

    // Initial focus: the row the user came back from, otherwise the first one.
    LaunchedEffect(Unit) {
        if (!keyboardNavigation) return@LaunchedEffect
        val index = items.indexOfFirst { it.key == lastOpened }.takeIf { it >= 0 } ?: 0
        listState.scrollToItem(index)
        withFrameNanos { }
        itemFocus[items[index].key]?.requestFocusSafely()
    }
}

private val FolderItem.key: String
    get() = when (this) {
        is FolderItem.Folder -> "folder:$path"
        is FolderItem.File -> uri
    }
