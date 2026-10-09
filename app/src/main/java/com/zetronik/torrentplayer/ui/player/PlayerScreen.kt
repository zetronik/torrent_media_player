package com.zetronik.torrentplayer.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.torrent.StreamBudget
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppIconButton
import com.zetronik.torrentplayer.ui.common.DialogOption
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.OptionsDialog
import com.zetronik.torrentplayer.ui.common.TouchTarget
import com.zetronik.torrentplayer.ui.common.formatDuration
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.formatSpeed
import com.zetronik.torrentplayer.ui.common.isKeyboardNavigation
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.common.toast
import com.zetronik.torrentplayer.ui.navigation.RemoteRoute
import com.zetronik.torrentplayer.ui.remote.CastDialog
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalIsTv
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding
import kotlinx.coroutines.delay

private enum class FocusTarget { PlayPause, SeekBar }
private enum class PlayerDialog { Audio, Subtitles, Playlist, Cast }

private const val CONTROLS_TIMEOUT_MS = 5_000L
private const val TOUCH_SEEK_MS = 10_000L
private const val MEDIA_KEY_SEEK_MS = 30_000L
/** Space between the lifted subtitles and the seek bar, as a fraction of the view height. */
private const val SUBTITLE_GAP_FRACTION = 0.02f
private const val SCALE_HINT_MS = 1_500L

/**
 * Remote control:
 *  - controls hidden: OK toggles pause, ←/→ seek (faster while held), ↑/↓/Menu show controls;
 *  - controls shown: regular focus navigation, ←/→ on the seek bar seek;
 *  - Back hides the controls first, then leaves the player.
 * Touch: tap toggles controls, double tap on the left/right half seeks by 10 s.
 */
@Composable
fun PlayerScreen(onExit: () -> Unit, onCast: (RemoteRoute) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: PlayerViewModel = viewModel {
        PlayerViewModel(context.applicationContext, createSavedStateHandle(), context.appContainer)
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val isTorrent = viewModel.route.infoHash != null
    // Sending to a TV, from a phone or tablet: the TV downloads a torrent itself, local videos are streamed.
    val canCast = !LocalIsTv.current
    val compact = AppTheme.layout.isCompact
    val errorText = when {
        ui.insufficientSpaceBytes != null -> stringResource(
            R.string.player_insufficient_space,
            resources.formatSize(ui.insufficientSpaceBytes ?: 0),
            resources.formatSize(StreamBudget.minimumFreeBytes),
        )
        ui.error != null -> stringResource(R.string.player_error_details, ui.error.orEmpty())
        else -> null
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var focusTarget by remember { mutableStateOf(FocusTarget.PlayPause) }
    // Bumped on every user interaction to restart the auto-hide timer.
    var interactions by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    // Top of the seek bar in window pixels; subtitles are lifted above it while the controls are shown.
    var seekBarTop by remember { mutableFloatStateOf(0f) }
    // Where the scale button shows no label (compact layout), the new mode is announced over the video.
    var scaleHint by remember { mutableStateOf<VideoScale?>(null) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    // Key-ups whose key-down already acted while the controls were hidden. Without this, the OK key-up
    // would land on the freshly focused play button and toggle playback a second time.
    val swallowedKeyUps = remember { mutableSetOf<Key>() }

    fun showControls(target: FocusTarget) {
        focusTarget = target
        controlsVisible = true
        interactions++
    }

    ImmersivePlayback()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
    BackHandler {
        if (controlsVisible && errorText == null) controlsVisible = false else onExit()
    }

    LaunchedEffect(controlsVisible, interactions, ui.isPlaying, dialog) {
        if (controlsVisible && ui.isPlaying && dialog == null) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    // On touch, focus stays on the root: a focused button would just show a stray highlight.
    val keyboardNavigation = isKeyboardNavigation()
    LaunchedEffect(controlsVisible, focusTarget, errorText, keyboardNavigation) {
        withFrameNanos { }
        when {
            errorText != null -> Unit
            !controlsVisible || !keyboardNavigation -> rootFocus.requestFocusSafely()
            focusTarget == FocusTarget.SeekBar -> seekFocus.requestFocusSafely()
            else -> playFocus.requestFocusSafely()
        }
    }
    LaunchedEffect(scaleHint) {
        if (scaleHint != null) {
            delay(SCALE_HINT_MS)
            scaleHint = null
        }
    }
    LaunchedEffect(ui.exitRequested) {
        if (ui.exitRequested) onExit()
    }
    LaunchedEffect(ui.resumedFromMs) {
        ui.resumedFromMs?.let {
            context.toast(resources.getString(R.string.player_resumed_from, formatDuration(it)))
            viewModel.consumeResumeNotice()
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (dialog != null) return false
        if (event.type == KeyEventType.KeyUp) return swallowedKeyUps.remove(event.key)
        if (event.type != KeyEventType.KeyDown) return false
        interactions++
        when (event.key) {
            Key.MediaPlayPause -> viewModel.togglePlayPause()
            Key.MediaPlay -> viewModel.player.play()
            Key.MediaPause -> viewModel.pause()
            Key.MediaFastForward -> viewModel.seekBy(MEDIA_KEY_SEEK_MS)
            Key.MediaRewind -> viewModel.seekBy(-MEDIA_KEY_SEEK_MS)
            else -> {
                if (controlsVisible || errorText != null) return false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        viewModel.togglePlayPause()
                        showControls(FocusTarget.PlayPause)
                    }
                    Key.DirectionLeft -> {
                        viewModel.seekBy(-seekStep(event))
                        showControls(FocusTarget.SeekBar)
                    }
                    Key.DirectionRight -> {
                        viewModel.seekBy(seekStep(event))
                        showControls(FocusTarget.SeekBar)
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.Menu -> showControls(FocusTarget.PlayPause)
                    else -> return false
                }
                swallowedKeyUps += event.key
            }
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(::handleKey)
            .focusRequester(rootFocus)
            .focusable()
    ) {
        // A fresh surface for every playlist item: Realtek TV decoders fail to start a 4K stream on a surface
        // that the previous 4K decoder has just left (ERROR_CODE_DECODING_FAILED on the next item).
        key(ui.currentIndex) {
            VideoSurface(viewModel, ui.scale, subtitlesAbove = if (controlsVisible && errorText == null) seekBarTop else 0f)
        }

        // Touch layer above the video view, below the controls.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) controlsVisible = false else showControls(FocusTarget.PlayPause)
                        },
                        onDoubleTap = { offset ->
                            viewModel.seekBy(if (offset.x < size.width / 2) -TOUCH_SEEK_MS else TOUCH_SEEK_MS)
                            showControls(FocusTarget.SeekBar)
                        },
                    )
                }
        )

        // With the controls shown, the spinner sits in the play button instead.
        if (errorText == null && !controlsVisible && (ui.preparing || ui.isBuffering)) {
            BufferingIndicator(viewModel, isTorrent, Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = controlsVisible && errorText == null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(
                viewModel = viewModel,
                ui = ui,
                isTorrent = isTorrent,
                canCast = canCast,
                playFocus = playFocus,
                seekFocus = seekFocus,
                onOpenDialog = { dialog = it },
                onOpenSettings = {
                    viewModel.pause()
                    onOpenSettings()
                },
                onInteraction = { interactions++ },
                onSeekBarPositioned = { seekBarTop = it },
                onCycleScale = {
                    interactions++
                    viewModel.cycleScale()
                    if (compact) scaleHint = ui.scale.next()
                },
                // Touch screens get a close button; a remote has its Back key.
                onClose = if (LocalIsTv.current) null else onExit,
            )
        }

        scaleHint?.let { ScaleHint(it, Modifier.align(Alignment.Center)) }

        errorText?.let { ErrorMessage(it, onClose = onExit, Modifier.align(Alignment.Center)) }
    }

    when (dialog) {
        PlayerDialog.Audio -> AudioDialog(viewModel, onDismiss = { dialog = null })
        PlayerDialog.Subtitles -> SubtitleDialog(viewModel, onDismiss = { dialog = null })
        PlayerDialog.Playlist -> PlaylistDialog(viewModel, ui, onDismiss = { dialog = null })
        PlayerDialog.Cast -> CastDialog(
            request = viewModel::castRequest,
            onCasted = { remote ->
                viewModel.pause()
                dialog = null
                onCast(remote)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(viewModel: PlayerViewModel, scale: VideoScale, subtitlesAbove: Float) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // Focus and keys stay in Compose.
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                subtitleView?.apply {
                    setApplyEmbeddedStyles(true)
                    // White text with a black outline and no box behind it; embedded ASS styles still apply.
                    setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        )
                    )
                    setUserDefaultTextSize()
                }
                player = viewModel.player
            }
        },
        update = { view ->
            view.resizeMode = scale.resizeMode
            // The view fills the window, so window coordinates match its own.
            val lift = if (subtitlesAbove > 0 && view.height > 0) (view.height - subtitlesAbove) / view.height else 0f
            view.subtitleView?.setBottomPaddingFraction(
                if (lift > 0) (lift + SUBTITLE_GAP_FRACTION).coerceAtMost(0.6f) else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
            )
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BufferingIndicator(viewModel: PlayerViewModel, isTorrent: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LoadingIndicator(size = 56.dp)
        if (isTorrent) {
            val stats by viewModel.stats.collectAsStateWithLifecycle()
            Text(
                torrentStatsLine(stats),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun torrentStatsLine(stats: TorrentStats): String {
    val resources = LocalResources.current
    val line = stringResource(
        R.string.player_buffering_stats,
        resources.formatSpeed(stats.downloadRate),
        stats.connectedSeeds,
        stats.connectedPeers,
    )
    if (stats.budgetBytes <= 0) return line
    // With a prebuffer target (download barely faster than the bitrate) show how far along it is.
    val buffer = if (stats.requiredMs > 0) {
        stringResource(R.string.player_buffer_target, stats.bufferedMs / 1000, stats.requiredMs / 1000)
    } else {
        stringResource(R.string.player_buffer_seconds, stats.bufferedMs / 1000)
    }
    return "$line · $buffer"
}

/**
 * Title and track/playlist buttons at the top; seek bar at the bottom with the time, playback buttons and
 * scale under it. D-pad focus moves between them geometrically.
 *
 * Compact (phone portrait): the buttons get a bar of their own above the title, the times go under the
 * ends of the seek bar and the scale button loses its label, so nothing is squeezed on a narrow screen.
 */
@Composable
private fun Controls(
    viewModel: PlayerViewModel,
    ui: PlayerViewModel.UiState,
    isTorrent: Boolean,
    canCast: Boolean,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    onOpenDialog: (PlayerDialog) -> Unit,
    onOpenSettings: () -> Unit,
    onInteraction: () -> Unit,
    onSeekBarPositioned: (top: Float) -> Unit,
    onCycleScale: () -> Unit,
    onClose: (() -> Unit)?,
) {
    // Collected only while the controls are composed, so position polling stops when they hide.
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val padding = LocalScreenPadding.current
    val layout = AppTheme.layout
    val hasPlaylist = ui.playlist.size > 1

    @Composable
    fun TitleBlock(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                ui.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = if (layout.isCompact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isTorrent) {
                val stats by viewModel.stats.collectAsStateWithLifecycle()
                Text(
                    torrentStatsLine(stats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    fun TopActions() {
        Row(horizontalArrangement = Arrangement.spacedBy(if (layout.isTv) 8.dp else 4.dp)) {
            if (ui.hasAudioChoice) {
                TopBarButton(R.drawable.ic_audio, stringResource(R.string.player_audio)) {
                    onOpenDialog(PlayerDialog.Audio)
                }
            }
            if (ui.hasSubtitles) {
                TopBarButton(R.drawable.ic_subtitles, stringResource(R.string.player_subtitles)) {
                    onOpenDialog(PlayerDialog.Subtitles)
                }
            }
            if (hasPlaylist) {
                TopBarButton(R.drawable.ic_playlist, stringResource(R.string.player_playlist)) {
                    onOpenDialog(PlayerDialog.Playlist)
                }
            }
            if (canCast) {
                TopBarButton(R.drawable.ic_cast, stringResource(R.string.remote_cast)) {
                    onOpenDialog(PlayerDialog.Cast)
                }
            }
            TopBarButton(R.drawable.ic_settings, stringResource(R.string.settings_title), onOpenSettings)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(padding)
                .padding(bottom = if (layout.isShort) 16.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (layout.isCompact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    onClose?.let { TopBarButton(R.drawable.ic_close, stringResource(R.string.close), it) }
                    Spacer(Modifier.weight(1f))
                    TopActions()
                }
                TitleBlock(Modifier.fillMaxWidth())
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onClose?.let { TopBarButton(R.drawable.ic_close, stringResource(R.string.close), it) }
                    TitleBlock(Modifier.weight(1f))
                    TopActions()
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(padding)
                .padding(top = if (layout.isShort) 24.dp else 48.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.isShort) 4.dp else 8.dp),
        ) {
            SeekBar(
                progress = progress,
                pendingSeekMs = ui.pendingSeekMs,
                onSeekBy = {
                    onInteraction()
                    viewModel.seekBy(it)
                },
                onSeekTo = { position, commit ->
                    onInteraction()
                    viewModel.seekTo(position, commit)
                },
                modifier = Modifier
                    .focusRequester(seekFocus)
                    .onGloballyPositioned { onSeekBarPositioned(it.boundsInWindow().top) },
            )
            val position = formatDuration(ui.pendingSeekMs ?: progress.positionMs)
            val duration = formatDuration(progress.durationMs)
            if (layout.isCompact) {
                Row(Modifier.fillMaxWidth()) {
                    TimeText(position)
                    Spacer(Modifier.weight(1f))
                    TimeText(duration)
                }
            }
            // Equal weights on both sides keep the playback buttons centred whatever the time and scale widths.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (!layout.isCompact) TimeText("$position / $duration")
                }
                PlaybackButtons(viewModel, ui, hasPlaylist, playFocus)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    ScaleButton(ui.scale, showLabel = !layout.isCompact, onClick = onCycleScale)
                }
            }
        }
    }
}

@Composable
private fun TimeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Previous / play-pause / next; previous and next only when the playlist has more than one video. */
@Composable
private fun PlaybackButtons(
    viewModel: PlayerViewModel,
    ui: PlayerViewModel.UiState,
    hasPlaylist: Boolean,
    playFocus: FocusRequester,
) {
    val layout = AppTheme.layout
    val playSize = if (layout.isShort) 56.dp else 64.dp
    Row(
        Modifier.padding(horizontal = if (layout.isCompact) 8.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (layout.isCompact) 16.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasPlaylist) {
            RoundButton(
                icon = R.drawable.ic_skip_previous,
                label = stringResource(R.string.player_previous),
                size = 48.dp,
                enabled = ui.hasPrevious,
                onClick = viewModel::playPrevious,
            )
        }
        AppIconButton(
            onClick = viewModel::togglePlayPause,
            modifier = Modifier
                .size(playSize)
                .focusRequester(playFocus),
            colors = roundButtonColors(),
        ) {
            if (ui.preparing || ui.isBuffering) {
                LoadingIndicator(Modifier.align(Alignment.Center), size = playSize / 2)
            } else {
                Icon(
                    painterResource(if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (ui.isPlaying) R.string.player_pause else R.string.player_play),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(playSize * 0.56f),
                )
            }
        }
        if (hasPlaylist) {
            RoundButton(
                icon = R.drawable.ic_skip_next,
                label = stringResource(R.string.player_next),
                size = 48.dp,
                enabled = ui.hasNext,
                onClick = viewModel::playNext,
            )
        }
    }
}

/** Video scale: icon and current mode where there is room, icon only in the compact layout. */
@Composable
private fun ScaleButton(scale: VideoScale, showLabel: Boolean, onClick: () -> Unit) {
    if (!showLabel) {
        TopBarButton(R.drawable.ic_aspect_ratio, stringResource(scale.label), onClick)
        return
    }
    AppButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.2f),
            pressedContentColor = Color.White,
        ),
    ) {
        Icon(painterResource(R.drawable.ic_aspect_ratio), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(scale.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The scale mode just picked, shown briefly in the middle of the video. */
@Composable
private fun ScaleHint(scale: VideoScale, modifier: Modifier) {
    Text(
        stringResource(scale.label),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Icon-only button on a transparent background: close, audio, subtitles, playlist, cast, settings, scale. */
@Composable
private fun TopBarButton(icon: Int, label: String, onClick: () -> Unit) {
    val size = if (LocalIsTv.current) 52.dp else TouchTarget
    AppIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.2f),
            pressedContentColor = Color.White,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.align(Alignment.Center).size(size * 0.54f))
    }
}

@Composable
internal fun RoundButton(
    icon: Int,
    label: String,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    colors: ButtonColors = roundButtonColors(),
) {
    AppIconButton(onClick = onClick, modifier = Modifier.size(size), enabled = enabled, colors = colors) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.align(Alignment.Center).size(size * 0.55f))
    }
}

/** Translucent dark circles that stay readable over any frame; white when focused. */
@Composable
internal fun roundButtonColors() = IconButtonDefaults.colors(
    containerColor = Color.Black.copy(alpha = 0.45f),
    contentColor = Color.White,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
    pressedContainerColor = Color.Black.copy(alpha = 0.7f),
    pressedContentColor = Color.White,
    disabledContainerColor = Color.Black.copy(alpha = 0.25f),
    disabledContentColor = Color.White.copy(alpha = 0.35f),
)

@Composable
internal fun SeekBar(
    progress: PlayerViewModel.Progress,
    pendingSeekMs: Long?,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (positionMs: Long, commit: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White,
) {
    var focused by remember { mutableStateOf(false) }
    val duration = progress.durationMs
    val position = pendingSeekMs ?: progress.positionMs
    val primary = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .fillMaxWidth()
            // Taller on touch screens, so the thin track is easy to grab.
            .height(if (LocalIsTv.current) 24.dp else 32.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onSeekBy(-seekStep(event))
                    Key.DirectionRight -> onSeekBy(seekStep(event))
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable()
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { offset -> onSeekTo((offset.x / size.width * duration).toLong(), true) }
            }
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                var target = 0L
                detectHorizontalDragGestures(
                    onDragEnd = { onSeekTo(target, true) },
                ) { change, _ ->
                    target = (change.position.x / size.width * duration).toLong().coerceIn(0, duration)
                    onSeekTo(target, false)
                }
            }
    ) {
        val trackHeight = if (focused) 8.dp.toPx() else 4.dp.toPx()
        val top = (size.height - trackHeight) / 2
        val radius = CornerRadius(trackHeight / 2)
        fun fraction(ms: Long) = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f

        drawRoundRect(trackColor.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, trackHeight), radius)
        drawRoundRect(
            trackColor.copy(alpha = 0.45f),
            Offset(0f, top),
            Size(size.width * fraction(progress.bufferedMs), trackHeight),
            radius,
        )
        val playedWidth = size.width * fraction(position)
        drawRoundRect(primary, Offset(0f, top), Size(playedWidth, trackHeight), radius)
        if (focused) drawCircle(trackColor, radius = 8.dp.toPx(), center = Offset(playedWidth, size.height / 2))
    }
}

@Composable
private fun ErrorMessage(text: String, onClose: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        AppButton(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.close)) }
    }
    val keyboardNavigation = isKeyboardNavigation()
    LaunchedEffect(Unit) { if (keyboardNavigation) focus.requestFocusSafely() }
}

@Composable
private fun AudioDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val options = remember {
        viewModel.audioOptions().mapIndexed { i, option ->
            DialogOption(
                label = resources.trackLabel(option.format, i + 1, external = false),
                selected = option.selected,
                onSelect = { viewModel.selectTrack(option) },
            )
        }
    }
    OptionsDialog(stringResource(R.string.player_audio_title), options, onDismiss)
}

@Composable
private fun SubtitleDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val off = stringResource(R.string.player_subtitles_off)
    val options = remember {
        val subtitlesOff = viewModel.isSubtitlesOff()
        listOf(DialogOption(off, subtitlesOff) { viewModel.disableSubtitles() }) +
            viewModel.subtitleOptions().mapIndexed { i, option ->
                DialogOption(
                    label = resources.trackLabel(option.format, i + 1, option.isExternal),
                    selected = !subtitlesOff && option.selected,
                    onSelect = { viewModel.selectTrack(option) },
                )
            }
    }
    OptionsDialog(stringResource(R.string.player_subtitles_title), options, onDismiss)
}

@Composable
private fun PlaylistDialog(viewModel: PlayerViewModel, ui: PlayerViewModel.UiState, onDismiss: () -> Unit) {
    val options = ui.playlist.mapIndexed { i, item ->
        DialogOption(label = item.title, selected = i == ui.currentIndex, onSelect = { viewModel.playItem(i) })
    }
    OptionsDialog(stringResource(R.string.player_playlist), options, onDismiss)
}

/** Holding the key speeds seeking up: 10 s, then 30 s, then a minute per repeat. */
internal fun seekStep(event: KeyEvent): Long {
    val repeats = event.nativeKeyEvent.repeatCount
    return when {
        repeats < 4 -> 10_000L
        repeats < 12 -> 30_000L
        else -> 60_000L
    }
}

/** Hides system bars on phones and keeps the screen on while the player is visible. */
@Composable
private fun ImmersivePlayback() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}
