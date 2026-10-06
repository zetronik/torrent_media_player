package com.zetronik.torrentplayer.ui.torrent

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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.data.PlaybackPosition
import com.zetronik.torrentplayer.torrent.TorrentException
import com.zetronik.torrentplayer.torrent.TorrentFileEntry
import com.zetronik.torrentplayer.torrent.TorrentMeta
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.ScreenHeader
import com.zetronik.torrentplayer.ui.common.formatDuration
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.formatSpeed
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.common.rememberItemFocusRequester
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

@Composable
fun TorrentScreen(
    onPlay: (TorrentMeta, TorrentFileEntry) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TorrentViewModel = viewModel {
        TorrentViewModel(createSavedStateHandle(), context.appContainer)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(LocalScreenPadding.current),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (val current = state) {
            TorrentViewModel.State.Resolving ->
                Progress(stringResource(R.string.torrent_resolving), details = null)
            TorrentViewModel.State.FetchingMetadata ->
                Progress(
                    stringResource(R.string.torrent_fetching_metadata),
                    stringResource(
                        R.string.torrent_metadata_progress,
                        stats.connectedSeeds + stats.connectedPeers,
                        stats.dhtNodes.toInt(),
                    ),
                )
            is TorrentViewModel.State.Failed -> Failure(current, onRetry = viewModel::retry, onBack = onBack)
            is TorrentViewModel.State.Ready -> FileList(
                meta = current.meta,
                stats = stats,
                positions = positions,
                onPlay = { onPlay(current.meta, it) },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun Progress(title: String, details: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator()
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (details != null) {
            Text(
                details,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Failure(state: TorrentViewModel.State.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    val text = when (state.reason) {
        TorrentException.Reason.INVALID_INPUT -> stringResource(R.string.error_invalid_input)
        TorrentException.Reason.DOWNLOAD_FAILED -> stringResource(R.string.error_download_failed)
        TorrentException.Reason.INVALID_TORRENT -> stringResource(R.string.error_invalid_torrent)
        TorrentException.Reason.ADD_FAILED, TorrentException.Reason.NOT_ACTIVE,
        TorrentException.Reason.INSUFFICIENT_SPACE, null ->
            state.message?.let { stringResource(R.string.error_generic, it) } ?: stringResource(R.string.error_add_failed)
    }
    val canRetry = state.reason != TorrentException.Reason.INVALID_INPUT &&
        state.reason != TorrentException.Reason.INVALID_TORRENT
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        AppButton(
            onClick = if (canRetry) onRetry else onBack,
            modifier = Modifier.focusRequester(focus),
        ) {
            Text(stringResource(if (canRetry) R.string.retry else R.string.cancel))
        }
    }
    val keyboardNavigation = isKeyboardNavigation()
    LaunchedEffect(Unit) { if (keyboardNavigation) focus.requestFocusSafely() }
}

@Composable
private fun FileList(
    meta: TorrentMeta,
    stats: TorrentStats,
    positions: Map<Int, PlaybackPosition>,
    onPlay: (TorrentFileEntry) -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    // Initial focus only helps D-pad/keyboard users; on touch it would just show a stray highlight.
    val keyboardNavigation = isKeyboardNavigation()
    val videos = meta.videoFiles
    val listState = rememberLazyListState()
    var lastPlayed by rememberSaveable { mutableIntStateOf(-1) }
    val itemFocus = remember { HashMap<Int, FocusRequester>() }

    Column(
        Modifier
            .readableWidth()
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = meta.name,
            subtitle = listOf(
                resources.formatSize(meta.totalSize),
                stringResource(R.string.torrent_swarm, stats.seeds, stats.peers),
                resources.formatSpeed(stats.downloadRate),
            ).joinToString(" · "),
            titleMaxLines = 3,
            largeTitle = false,
            onBack = onBack,
        )
        if (videos.isEmpty()) {
            Text(
                stringResource(R.string.torrent_no_video),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(listItemSpacing()),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(videos, key = { _, file -> file.index }) { index, file ->
                val position = positions[file.index]
                AppListItem(
                    selected = false,
                    position = ItemPosition.of(index, videos.size),
                    onClick = {
                        lastPlayed = file.index
                        onPlay(file)
                    },
                    modifier = Modifier.focusRequester(rememberItemFocusRequester(itemFocus, file.index)),
                    headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val details = buildList {
                            add(resources.formatSize(file.size))
                            if (position != null) {
                                add(
                                    stringResource(
                                        R.string.torrent_resume_at,
                                        formatDuration(position.positionMs),
                                        formatDuration(position.durationMs),
                                    )
                                )
                            }
                        }
                        Text(details.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { IconBadge(if (position != null) R.drawable.ic_play else R.drawable.ic_movie) },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!keyboardNavigation || videos.isEmpty()) return@LaunchedEffect
        val index = videos.indexOfFirst { it.index == lastPlayed }.takeIf { it >= 0 } ?: 0
        listState.scrollToItem(index)
        withFrameNanos { }
        itemFocus[videos[index].index]?.requestFocusSafely()
    }
}
