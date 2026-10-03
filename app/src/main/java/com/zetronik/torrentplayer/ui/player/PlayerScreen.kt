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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.DialogOption
import com.zetronik.torrentplayer.ui.common.OptionsDialog
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.torrent.StreamBudget
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.formatDuration
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.formatSpeed
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.common.toast
import com.zetronik.torrentplayer.ui.theme.LocalScreenPadding
import kotlinx.coroutines.delay

private enum class FocusTarget { PlayPause, SeekBar }
private enum class TrackDialogKind { Audio, Subtitles }

private const val CONTROLS_TIMEOUT_MS = 5_000L
private const val TOUCH_SEEK_MS = 10_000L
private const val MEDIA_KEY_SEEK_MS = 30_000L

/**
 * Remote control:
 *  - controls hidden: OK toggles pause, ←/→ seek (faster while held), ↑/↓/Menu show controls;
 *  - controls shown: regular focus navigation, ←/→ on the seek bar seek;
 *  - Back hides the controls first, then leaves the player.
 * Touch: tap toggles controls, double tap on the left/right half seeks by 10 s.
 */
@Composable
fun PlayerScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: PlayerViewModel = viewModel {
        PlayerViewModel(context.applicationContext, createSavedStateHandle(), context.appContainer)
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val isTorrent = viewModel.route.infoHash != null
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
    var dialog by remember { mutableStateOf<TrackDialogKind?>(null) }
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
    LaunchedEffect(controlsVisible, focusTarget, errorText) {
        withFrameNanos { }
        when {
            errorText != null -> Unit
            !controlsVisible -> rootFocus.requestFocusSafely()
            focusTarget == FocusTarget.SeekBar -> seekFocus.requestFocusSafely()
            else -> playFocus.requestFocusSafely()
        }
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
        VideoSurface(viewModel)

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

        if (errorText == null && (ui.preparing || ui.isBuffering)) {
            BufferingIndicator(viewModel, isTorrent, Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = controlsVisible && errorText == null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(
                viewModel = viewModel,
                ui = ui,
                isTorrent = isTorrent,
                playFocus = playFocus,
                seekFocus = seekFocus,
                onOpenDialog = { dialog = it },
                onInteraction = { interactions++ },
            )
        }

        errorText?.let { ErrorMessage(it, onClose = onExit, Modifier.align(Alignment.Center)) }
    }

    when (dialog) {
        TrackDialogKind.Audio -> AudioDialog(viewModel, onDismiss = { dialog = null })
        TrackDialogKind.Subtitles -> SubtitleDialog(viewModel, onDismiss = { dialog = null })
        null -> Unit
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(viewModel: PlayerViewModel) {
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
                    setUserDefaultStyle()
                    setUserDefaultTextSize()
                }
                player = viewModel.player
            }
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
                modifier = Modifier
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

@Composable
private fun Controls(
    viewModel: PlayerViewModel,
    ui: PlayerViewModel.UiState,
    isTorrent: Boolean,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    onOpenDialog: (TrackDialogKind) -> Unit,
    onInteraction: () -> Unit,
) {
    // Collected only while the controls are composed, so position polling stops when they hide.
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val padding = LocalScreenPadding.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(padding)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            viewModel.route.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isTorrent) {
            val stats by viewModel.stats.collectAsStateWithLifecycle()
            Text(torrentStatsLine(stats), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
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
            modifier = Modifier.focusRequester(seekFocus),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatDuration(ui.pendingSeekMs ?: progress.positionMs)} / ${formatDuration(progress.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlButton(
                    icon = if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    label = stringResource(if (ui.isPlaying) R.string.player_pause else R.string.player_play),
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.focusRequester(playFocus),
                )
                if (ui.hasAudioChoice) {
                    ControlButton(
                        icon = R.drawable.ic_audio,
                        label = stringResource(R.string.player_audio),
                        onClick = { onOpenDialog(TrackDialogKind.Audio) },
                    )
                }
                if (ui.hasSubtitles) {
                    ControlButton(
                        icon = R.drawable.ic_subtitles,
                        label = stringResource(R.string.player_subtitles),
                        onClick = { onOpenDialog(TrackDialogKind.Subtitles) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppButton(onClick = onClick, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SeekBar(
    progress: PlayerViewModel.Progress,
    pendingSeekMs: Long?,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (positionMs: Long, commit: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val duration = progress.durationMs
    val position = pendingSeekMs ?: progress.positionMs
    val primary = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
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

        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, trackHeight), radius)
        drawRoundRect(
            Color.White.copy(alpha = 0.45f),
            Offset(0f, top),
            Size(size.width * fraction(progress.bufferedMs), trackHeight),
            radius,
        )
        val playedWidth = size.width * fraction(position)
        drawRoundRect(primary, Offset(0f, top), Size(playedWidth, trackHeight), radius)
        if (focused) drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(playedWidth, size.height / 2))
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
    LaunchedEffect(Unit) { focus.requestFocusSafely() }
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
                    label = resources.trackLabel(
                        option.format,
                        i + 1,
                        external = option.format.id?.contains("external:") == true,
                    ),
                    selected = !subtitlesOff && option.selected,
                    onSelect = { viewModel.selectTrack(option) },
                )
            }
    }
    OptionsDialog(stringResource(R.string.player_subtitles_title), options, onDismiss)
}

/** Holding the key speeds seeking up: 10 s, then 30 s, then a minute per repeat. */
private fun seekStep(event: KeyEvent): Long {
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
