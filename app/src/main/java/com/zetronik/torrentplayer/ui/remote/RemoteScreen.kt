package com.zetronik.torrentplayer.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.remote.RemoteAction
import com.zetronik.torrentplayer.remote.RemoteStatus
import com.zetronik.torrentplayer.remote.RemoteTrack
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppSecondaryButton
import com.zetronik.torrentplayer.ui.common.DialogOption
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.OptionsDialog
import com.zetronik.torrentplayer.ui.common.ScreenHeader
import com.zetronik.torrentplayer.ui.common.appDestructiveButtonColors
import com.zetronik.torrentplayer.ui.common.appIconButtonColors
import com.zetronik.torrentplayer.ui.common.formatDuration
import com.zetronik.torrentplayer.ui.common.readableWidth
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import com.zetronik.torrentplayer.ui.player.PlayerViewModel
import com.zetronik.torrentplayer.ui.player.RoundButton
import com.zetronik.torrentplayer.ui.player.SeekBar
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding

private enum class RemoteDialog { Audio, Subtitles, Playlist }

private const val REMOTE_SEEK_MS = 10_000L

/** Phone screen that controls the player on a TV. Leaving it keeps the TV playing. */
@Composable
fun RemoteScreen(onBack: () -> Unit, onContinueHere: (PlayerRoute) -> Unit) {
    val context = LocalContext.current
    val viewModel: RemoteViewModel = viewModel { RemoteViewModel(createSavedStateHandle(), context.appContainer) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<RemoteDialog?>(null) }
    val status = ui.status
    val layout = AppTheme.layout
    // Landscape phones and tablets: what is playing on the left, the controls on the right.
    val twoPane = layout.isLandscape && layout.widthDp >= 560

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(LocalScreenPadding.current),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .readableWidth(if (twoPane) 960.dp else 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(
                title = stringResource(R.string.remote_playing_on, viewModel.route.deviceName),
                largeTitle = false,
                titleMaxLines = 1,
                onBack = onBack,
            )
            when {
                status == null && !ui.connectionLost -> LoadingIndicator(
                    Modifier
                        .padding(32.dp)
                        .align(Alignment.CenterHorizontally)
                )
                status == null || !status.active -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(
                        stringResource(if (ui.connectionLost) R.string.remote_connection_lost else R.string.remote_inactive),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                    AppButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
                twoPane -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        NowPlaying(ui, status)
                        SessionButtons(viewModel, onBack, onContinueHere, stacked = true)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Transport(viewModel, ui, status, onOpenDialog = { dialog = it })
                    }
                }
                else -> {
                    NowPlaying(ui, status)
                    Transport(viewModel, ui, status, onOpenDialog = { dialog = it })
                    SessionButtons(viewModel, onBack, onContinueHere, stacked = layout.isCompact)
                }
            }
        }
    }

    if (status != null) {
        when (dialog) {
            RemoteDialog.Audio -> TrackDialog(
                title = stringResource(R.string.player_audio_title),
                tracks = status.audio,
                onSelect = { viewModel.command(RemoteAction.AUDIO, it.toLong()) },
                onDismiss = { dialog = null },
            )
            RemoteDialog.Subtitles -> {
                val off = DialogOption(stringResource(R.string.player_subtitles_off), status.subtitlesOff) {
                    viewModel.command(RemoteAction.SUBTITLES, -1)
                }
                TrackDialog(
                    title = stringResource(R.string.player_subtitles_title),
                    tracks = status.subtitles,
                    onSelect = { viewModel.command(RemoteAction.SUBTITLES, it.toLong()) },
                    onDismiss = { dialog = null },
                    leading = off,
                )
            }
            RemoteDialog.Playlist -> OptionsDialog(
                stringResource(R.string.player_playlist),
                status.playlist.mapIndexed { i, title ->
                    DialogOption(title, i == status.currentIndex) { viewModel.command(RemoteAction.PLAY_ITEM, i.toLong()) }
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
    }
}

/** Title of the video on the TV and its state (loading, error, lost connection). */
@Composable
private fun NowPlaying(ui: RemoteViewModel.UiState, status: RemoteStatus) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.card, RoundedCornerShape(AppTheme.shapes.card))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(R.drawable.ic_tv)
        Text(
            status.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val note = when {
            ui.connectionLost -> stringResource(R.string.remote_connection_lost)
            status.error != null -> stringResource(R.string.remote_tv_error, status.error)
            status.isBuffering -> stringResource(R.string.remote_loading)
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (status.error != null || ui.connectionLost) AppTheme.colors.destructive else AppTheme.colors.secondaryLabel,
            )
        }
    }
}

/** Seek bar, playback buttons and track buttons. */
@Composable
private fun Transport(
    viewModel: RemoteViewModel,
    ui: RemoteViewModel.UiState,
    status: RemoteStatus,
    onOpenDialog: (RemoteDialog) -> Unit,
) {
    val colors = AppTheme.colors
    val position = ui.pendingSeekMs ?: status.positionMs
    Column {
        SeekBar(
            progress = PlayerViewModel.Progress(status.positionMs, status.durationMs, status.bufferedMs),
            pendingSeekMs = ui.pendingSeekMs,
            onSeekBy = { viewModel.command(RemoteAction.SEEK_BY, it) },
            onSeekTo = viewModel::seekTo,
            trackColor = colors.label,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(position), style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(status.durationMs), style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
        }
    }

    val hasPlaylist = status.playlist.size > 1
    val buttonColors = appIconButtonColors()
    val playColors = IconButtonDefaults.colors(
        containerColor = colors.accent,
        contentColor = colors.onAccent,
        focusedContainerColor = colors.focused,
        focusedContentColor = colors.onFocused,
        pressedContainerColor = colors.label.copy(alpha = 0.12f).compositeOver(colors.accent),
        pressedContentColor = colors.onAccent,
    )
    // The row is scaled down as a whole when the window is narrower than it (small phones, split screen).
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val outer = 48.dp
        val inner = 56.dp
        val play = 72.dp
        val gap = 16.dp
        val needed = (if (hasPlaylist) outer * 2 + gap * 2 else 0.dp) + inner * 2 + play + gap * 2
        val factor = (maxWidth / needed).coerceAtMost(1f)
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap * factor),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPlaylist) {
                RoundButton(
                    icon = R.drawable.ic_skip_previous,
                    label = stringResource(R.string.player_previous),
                    size = outer * factor,
                    enabled = status.currentIndex > 0,
                    onClick = { viewModel.command(RemoteAction.PREVIOUS) },
                    colors = buttonColors,
                )
            }
            RoundButton(
                icon = R.drawable.ic_fast_rewind,
                label = stringResource(R.string.player_rewind),
                size = inner * factor,
                enabled = true,
                onClick = { viewModel.command(RemoteAction.SEEK_BY, -REMOTE_SEEK_MS) },
                colors = buttonColors,
            )
            RoundButton(
                icon = if (status.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                label = stringResource(if (status.isPlaying) R.string.player_pause else R.string.player_play),
                size = play * factor,
                enabled = true,
                onClick = { viewModel.command(RemoteAction.TOGGLE) },
                colors = playColors,
            )
            RoundButton(
                icon = R.drawable.ic_fast_forward,
                label = stringResource(R.string.player_forward),
                size = inner * factor,
                enabled = true,
                onClick = { viewModel.command(RemoteAction.SEEK_BY, REMOTE_SEEK_MS) },
                colors = buttonColors,
            )
            if (hasPlaylist) {
                RoundButton(
                    icon = R.drawable.ic_skip_next,
                    label = stringResource(R.string.player_next),
                    size = outer * factor,
                    enabled = status.currentIndex < status.playlist.lastIndex,
                    onClick = { viewModel.command(RemoteAction.NEXT) },
                    colors = buttonColors,
                )
            }
        }
    }

    // Wraps to a second line instead of squeezing the buttons.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status.audio.size > 1) {
            LabeledButton(R.drawable.ic_audio, stringResource(R.string.player_audio)) { onOpenDialog(RemoteDialog.Audio) }
        }
        if (status.subtitles.isNotEmpty()) {
            LabeledButton(R.drawable.ic_subtitles, stringResource(R.string.player_subtitles)) {
                onOpenDialog(RemoteDialog.Subtitles)
            }
        }
        if (hasPlaylist) {
            LabeledButton(R.drawable.ic_playlist, stringResource(R.string.player_playlist)) {
                onOpenDialog(RemoteDialog.Playlist)
            }
        }
    }
}

/** "Watch on phone" / "Stop on TV": full-width and stacked when [stacked], side by side otherwise. */
@Composable
private fun SessionButtons(
    viewModel: RemoteViewModel,
    onBack: () -> Unit,
    onContinueHere: (PlayerRoute) -> Unit,
    stacked: Boolean,
) {
    val continueHere: @Composable (Modifier) -> Unit = { modifier ->
        AppButton(onClick = { viewModel.continueHere(onContinueHere) }, modifier = modifier) {
            Text(stringResource(R.string.remote_continue_here), textAlign = TextAlign.Center)
        }
    }
    val stop: @Composable (Modifier) -> Unit = { modifier ->
        AppButton(onClick = { viewModel.stopOnTv(onBack) }, modifier = modifier, colors = appDestructiveButtonColors()) {
            Text(stringResource(R.string.remote_stop), textAlign = TextAlign.Center)
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            continueHere(Modifier.fillMaxWidth())
            stop(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            continueHere(Modifier.weight(1f))
            stop(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabeledButton(icon: Int, label: String, onClick: () -> Unit) {
    AppSecondaryButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun TrackDialog(
    title: String,
    tracks: List<RemoteTrack>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    leading: DialogOption? = null,
) {
    val options = listOfNotNull(leading) + tracks.mapIndexed { i, track ->
        DialogOption(track.label, track.selected) { onSelect(i) }
    }
    OptionsDialog(title, options, onDismiss)
}
