package com.zetronik.torrentplayer.ui.player

import android.content.Context
import android.util.Base64
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.data.PlaybackPositionRepository
import com.zetronik.torrentplayer.player.PlayerFactory
import com.zetronik.torrentplayer.player.StreamUris
import com.zetronik.torrentplayer.remote.PlayRequest
import com.zetronik.torrentplayer.remote.LocalStreamServer
import com.zetronik.torrentplayer.remote.RemoteAction
import com.zetronik.torrentplayer.remote.RemoteCommand
import com.zetronik.torrentplayer.remote.RemotePlayback
import com.zetronik.torrentplayer.remote.RemoteStatus
import com.zetronik.torrentplayer.remote.RemoteTrack
import com.zetronik.torrentplayer.torrent.SubtitleMatcher
import com.zetronik.torrentplayer.torrent.TorrentException
import com.zetronik.torrentplayer.torrent.TorrentMeta
import com.zetronik.torrentplayer.torrent.TorrentInput
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.torrent.TorrentUris
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerViewModel(
    context: Context,
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    data class UiState(
        val title: String,
        /** Videos that can be switched to: the torrent's video files or local videos opened together. */
        val playlist: List<PlaylistItem>,
        val currentIndex: Int,
        val scale: VideoScale,
        val preparing: Boolean = true,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val ended: Boolean = false,
        /** Target of a seek that is still being accumulated from repeated key presses. */
        val pendingSeekMs: Long? = null,
        val resumedFromMs: Long? = null,
        val error: String? = null,
        /** Set instead of [error] when the device lacks space to stream: free bytes left. */
        val insufficientSpaceBytes: Long? = null,
        val hasAudioChoice: Boolean = false,
        val hasSubtitles: Boolean = false,
        /** A phone asked to close the player (remote "stop"). */
        val exitRequested: Boolean = false,
    ) {
        val hasPrevious: Boolean get() = currentIndex > 0
        val hasNext: Boolean get() = currentIndex < playlist.lastIndex
    }

    data class Progress(val positionMs: Long, val durationMs: Long, val bufferedMs: Long)

    val route = savedStateHandle.toRoute<PlayerRoute>()
    private val savedState = savedStateHandle
    private val isTorrent = route.infoHash != null
    private val engine = container.torrentEngine
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resources = context.resources

    val player: ExoPlayer = PlayerFactory.create(context.applicationContext, engine, forTorrent = isTorrent)

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Torrent stats with [TorrentStats.bufferedMs] covering everything buffered: player memory plus disk. */
    val stats: StateFlow<TorrentStats> = (route.infoHash?.let { engine.stats(it) } ?: emptyFlow())
        .map { stats ->
            val inMemory = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            stats.copy(bufferedMs = inMemory + stats.bufferedMs.coerceAtLeast(0))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), TorrentStats.Empty)

    /** Polled only while someone collects it, i.e. while the controls are on screen. */
    val progress: StateFlow<Progress> = flow {
        while (true) {
            emit(Progress(player.currentPosition, player.duration.coerceAtLeast(0), player.bufferedPosition))
            delay(PROGRESS_INTERVAL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), Progress(0, 0, 0))

    private var seekJob: Job? = null
    private var loadJob: Job? = null
    /** Size of the streamed torrent file, used to derive its bitrate once the duration is known. */
    private var videoSize = 0L
    /** Becomes true once playback was ready; buffering after that is a stall, not the initial load. */
    private var hasPlayed = false
    private val remote: RemotePlayback = RemoteControl()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _ui.update {
                it.copy(
                    preparing = it.preparing && playbackState != Player.STATE_READY,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    ended = playbackState == Player.STATE_ENDED,
                )
            }
            if (playbackState == Player.STATE_ENDED && _ui.value.hasNext) {
                playNext()
                return
            }
            if (playbackState == Player.STATE_READY) {
                hasPlayed = true
                reportBitrate()
            }
            route.infoHash?.let { engine.setPlayerStalled(it, hasPlayed && playbackState == Player.STATE_BUFFERING) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _ui.update {
                it.copy(
                    hasAudioChoice = audioOptions().size > 1,
                    hasSubtitles = tracks.groups.any { group -> group.type == C.TRACK_TYPE_TEXT },
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _ui.update { it.copy(error = error.errorCodeName, preparing = false) }
        }
    }

    init {
        player.addListener(listener)
        container.remoteSession.attach(remote)
        // A position handed over from another device applies once; after process death the saved one is newer.
        val handedOver = route.startPositionMs.takeIf { it >= 0 && savedState.get<Boolean>(KEY_STARTED) != true }
        savedState[KEY_STARTED] = true
        loadJob = viewModelScope.launch { prepare(currentItem(), handedOver, route.startDurationMs) }
        viewModelScope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) savePosition()
            }
        }
    }

    private fun initialState(): UiState {
        val scale = prefs.getString(PREF_SCALE, null)
            ?.let { name -> VideoScale.entries.firstOrNull { it.name == name } } ?: VideoScale.Fit
        val playlist: List<PlaylistItem>
        val index: Int
        if (isTorrent) {
            // The full list comes with the metadata; until then only the file being opened is known.
            val fileIndex = savedState.get<Int>(KEY_FILE_INDEX) ?: route.fileIndex
            val title = if (fileIndex == route.fileIndex) route.title else ""
            val uri = TorrentUris.build(checkNotNull(route.infoHash), fileIndex).toString()
            playlist = listOf(PlaylistItem(uri, title, fileIndex))
            index = 0
        } else {
            playlist = route.playlistUris
                .mapIndexed { i, uri -> PlaylistItem(uri, route.playlistTitles.getOrElse(i) { uri }) }
                .ifEmpty { listOf(PlaylistItem(route.uri, route.title)) }
            index = (savedState.get<Int>(KEY_LOCAL_INDEX) ?: playlist.indexOfFirst { it.uri == route.uri })
                .coerceIn(0, playlist.lastIndex)
        }
        return UiState(title = playlist[index].title, playlist = playlist, currentIndex = index, scale = scale)
    }

    private fun currentItem(): PlaylistItem = _ui.value.let { it.playlist[it.currentIndex] }

    /** Null for a phone's stream: its URI changes with every cast, and the phone keeps its own position. */
    private fun positionKey(item: PlaylistItem): String? = when {
        isTorrent -> PlaybackPositionRepository.torrentKey(checkNotNull(route.infoHash), item.fileIndex)
        item.uri.startsWith("${StreamUris.SCHEME}:") -> null
        else -> item.uri
    }

    /** [startOverrideMs] replaces the saved position; [durationHintMs] is the duration it was measured against. */
    private suspend fun prepare(item: PlaylistItem, startOverrideMs: Long? = null, durationHintMs: Long = 0) {
        val saved = positionKey(item)?.let { container.positionRepository.get(it) }
        val startMs = startOverrideMs ?: saved?.positionMs ?: 0L
        val durationMs = if (startOverrideMs != null && durationHintMs > 0) durationHintMs else saved?.durationMs ?: 0L
        val mediaItem = try {
            if (isTorrent) {
                buildTorrentItem(item.fileIndex, startMs, durationMs)
            } else {
                MediaItem.fromUri(item.uri)
            }
        } catch (e: TorrentException) {
            _ui.update {
                if (e.reason == TorrentException.Reason.INSUFFICIENT_SPACE) {
                    it.copy(insufficientSpaceBytes = e.freeBytes, preparing = false)
                } else {
                    it.copy(error = e.reason.name, preparing = false)
                }
            }
            return
        }
        player.setMediaItem(mediaItem, startMs)
        player.prepare()
        player.playWhenReady = true
        if (startMs > 0) _ui.update { it.copy(resumedFromMs = startMs) }
    }

    private suspend fun buildTorrentItem(fileIndex: Int, startMs: Long, durationMs: Long): MediaItem {
        val infoHash = checkNotNull(route.infoHash)
        // A no-op while the torrent screen keeps it active; re-adds it from saved metadata after process death.
        engine.add(TorrentInput.Magnet("magnet:?xt=urn:btih:$infoHash"))
        val meta = engine.awaitMetadata(infoHash)
        updatePlaylist(meta, fileIndex)
        val video = meta.files.first { it.index == fileIndex }
        val subtitles = SubtitleMatcher.find(video, meta.files)
        videoSize = video.size

        // Byte offset of the resume point, estimated from the saved duration; the exact one comes from the demuxer.
        val startByte = if (durationMs > 0) (video.size.toDouble() * startMs / durationMs).toLong() else 0L
        engine.prepareStream(infoHash, video.index, subtitles.map { it.file.index }, startByte)

        return MediaItem.Builder()
            .setUri(TorrentUris.build(infoHash, video.index))
            .setSubtitleConfigurations(
                subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(TorrentUris.build(infoHash, sub.file.index))
                        .setMimeType(subtitleMimeType(sub.file.extension))
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .setId("external:${sub.file.index}")
                        .build()
                }
            )
            .build()
    }

    private fun updatePlaylist(meta: TorrentMeta, fileIndex: Int) {
        val playlist = meta.videoFiles.map {
            PlaylistItem(TorrentUris.build(meta.infoHash, it.index).toString(), it.name, it.index)
        }
        val index = playlist.indexOfFirst { it.fileIndex == fileIndex }
        if (index < 0) return
        _ui.update { it.copy(playlist = playlist, currentIndex = index, title = playlist[index].title) }
    }

    /**
     * Switches to another video of the playlist. For a torrent the previous file's cache is dropped first,
     * so the disk budget is always spent on the file being played.
     */
    fun playItem(index: Int, startMs: Long? = null) {
        val state = _ui.value
        if (index !in state.playlist.indices || index == state.currentIndex) return
        savePosition()
        seekJob?.cancel()
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        hasPlayed = false
        videoSize = 0
        val item = state.playlist[index]
        if (isTorrent) savedState[KEY_FILE_INDEX] = item.fileIndex else savedState[KEY_LOCAL_INDEX] = index
        _ui.update { UiState(title = item.title, playlist = it.playlist, currentIndex = index, scale = it.scale) }
        loadJob = viewModelScope.launch {
            route.infoHash?.let { engine.resetData(it) }
            prepare(item, startMs)
        }
    }

    fun playPrevious() = playItem(_ui.value.currentIndex - 1)

    fun playNext() = playItem(_ui.value.currentIndex + 1)

    fun cycleScale() {
        val scale = _ui.value.scale.next()
        prefs.edit { putString(PREF_SCALE, scale.name) }
        _ui.update { it.copy(scale = scale) }
    }

    /** Lets the engine size its read-ahead in seconds of video rather than in bytes. */
    private fun reportBitrate() {
        val infoHash = route.infoHash ?: return
        val durationMs = player.duration
        if (videoSize <= 0 || durationMs == C.TIME_UNSET || durationMs <= 0) return
        engine.setBitrate(infoHash, videoSize * 1000 / durationMs)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun pause() = player.pause()

    /**
     * Accumulates relative seeks (key repeat, double taps) and applies them once input settles:
     * every applied seek restarts torrent prioritization and buffering, so intermediate targets are skipped.
     */
    fun seekBy(deltaMs: Long) {
        val base = _ui.value.pendingSeekMs ?: player.currentPosition
        seekTo(base + deltaMs, commitImmediately = false)
    }

    fun seekTo(positionMs: Long, commitImmediately: Boolean = true) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = positionMs.coerceIn(0, duration ?: Long.MAX_VALUE)
        seekJob?.cancel()
        if (commitImmediately) {
            player.seekTo(target)
            _ui.update { it.copy(pendingSeekMs = null) }
            return
        }
        _ui.update { it.copy(pendingSeekMs = target) }
        seekJob = viewModelScope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            player.seekTo(target)
            _ui.update { it.copy(pendingSeekMs = null) }
        }
    }

    /**
     * What the TV needs to continue from the current position. A torrent is downloaded by the TV itself;
     * local videos (with the rest of the playlist) are streamed from this phone.
     */
    suspend fun castRequest(): PlayRequest? {
        val item = currentItem()
        val positionMs = _ui.value.pendingSeekMs ?: player.currentPosition
        val durationMs = player.duration.coerceAtLeast(0)
        val infoHash = route.infoHash ?: return withContext(Dispatchers.IO) {
            val sources = _ui.value.playlist.map { LocalStreamServer.Source(it.uri, it.title) }
            PlayRequest(
                title = item.title,
                positionMs = positionMs,
                durationMs = durationMs,
                stream = container.localStreamServer.offer(sources, _ui.value.currentIndex),
            )
        }
        val meta = engine.awaitMetadata(infoHash)
        val torrent = withContext(Dispatchers.IO) {
            engine.metadataFile(infoHash).takeIf { it.exists() }?.readBytes()
        }
        return PlayRequest(
            infoHash = infoHash,
            fileIndex = item.fileIndex,
            title = item.title,
            positionMs = positionMs,
            durationMs = durationMs,
            magnet = meta.magnetUri,
            torrent = torrent?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        )
    }

    /** Commands from a paired phone while this player runs on the TV. */
    private inner class RemoteControl : RemotePlayback {
        override val infoHash: String? get() = route.infoHash

        override fun status(): RemoteStatus {
            val state = _ui.value
            val subtitlesOff = isSubtitlesOff()
            return RemoteStatus(
                active = true,
                title = state.title,
                infoHash = route.infoHash,
                fileIndex = currentItem().fileIndex,
                positionMs = state.pendingSeekMs ?: player.currentPosition,
                durationMs = player.duration.coerceAtLeast(0),
                bufferedMs = player.bufferedPosition,
                isPlaying = state.isPlaying,
                isBuffering = state.preparing || state.isBuffering,
                error = state.error ?: state.insufficientSpaceBytes?.let { TorrentException.Reason.INSUFFICIENT_SPACE.name },
                playlist = state.playlist.map { it.title },
                currentIndex = state.currentIndex,
                audio = audioOptions().mapIndexed { i, option ->
                    RemoteTrack(resources.trackLabel(option.format, i + 1, external = false), option.selected)
                },
                subtitles = subtitleOptions().mapIndexed { i, option ->
                    RemoteTrack(resources.trackLabel(option.format, i + 1, option.isExternal), !subtitlesOff && option.selected)
                },
                subtitlesOff = subtitlesOff,
            )
        }

        override fun execute(command: RemoteCommand) {
            val index = command.value.toInt()
            when (command.action) {
                RemoteAction.TOGGLE -> togglePlayPause()
                RemoteAction.SEEK_TO -> seekTo(command.value)
                RemoteAction.SEEK_BY -> seekBy(command.value)
                RemoteAction.PREVIOUS -> playPrevious()
                RemoteAction.NEXT -> playNext()
                RemoteAction.PLAY_ITEM -> playItem(index)
                RemoteAction.AUDIO -> audioOptions().getOrNull(index)?.let(::selectTrack)
                RemoteAction.SUBTITLES ->
                    if (index < 0) disableSubtitles() else subtitleOptions().getOrNull(index)?.let(::selectTrack)
                RemoteAction.STOP -> _ui.update { it.copy(exitRequested = true) }
            }
        }

        override fun playFile(fileIndex: Int, positionMs: Long) {
            val index = _ui.value.playlist.indexOfFirst { it.fileIndex == fileIndex }
            if (index < 0) return
            if (index == _ui.value.currentIndex) {
                seekTo(positionMs)
                player.play()
            } else {
                playItem(index, positionMs)
            }
        }
    }

    fun audioOptions(): List<TrackOption> = trackOptions(C.TRACK_TYPE_AUDIO)

    fun subtitleOptions(): List<TrackOption> = trackOptions(C.TRACK_TYPE_TEXT)

    fun isSubtitlesOff(): Boolean =
        C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes ||
            player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

    fun selectTrack(option: TrackOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
            .setTrackTypeDisabled(option.group.type, false)
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun consumeResumeNotice() = _ui.update { it.copy(resumedFromMs = null) }

    private fun trackOptions(type: Int): List<TrackOption> = buildList {
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                add(TrackOption(group, i, group.getTrackFormat(i), group.isTrackSelected(i)))
            }
        }
    }

    private fun savePosition() {
        val position = player.currentPosition
        val duration = player.duration
        if (_ui.value.preparing || duration == C.TIME_UNSET) return
        val key = positionKey(currentItem()) ?: return
        container.appScope.launch { container.positionRepository.save(key, position, duration) }
    }

    override fun onCleared() {
        container.remoteSession.detach(remote)
        savePosition()
        player.removeListener(listener)
        // Release first: it stops the loader threads that read from the torrent.
        player.release()
        // Leaving the player drops the downloaded data; the torrent stays for the file list.
        route.infoHash?.let { hash -> container.appScope.launch { engine.resetData(hash) } }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
        const val SAVE_INTERVAL_MS = 15_000L
        const val SEEK_COMMIT_DELAY_MS = 700L
        const val PREFS_NAME = "player"
        const val PREF_SCALE = "video_scale"
        const val KEY_FILE_INDEX = "fileIndex"
        const val KEY_LOCAL_INDEX = "localIndex"
        const val KEY_STARTED = "started"

        fun subtitleMimeType(extension: String): String = when (extension) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

/** [fileIndex] is the file inside the torrent, -1 for local videos. */
data class PlaylistItem(val uri: String, val title: String, val fileIndex: Int = -1)

data class TrackOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val format: Format,
    val selected: Boolean,
) {
    /** Subtitles sideloaded from a separate file of the torrent. */
    val isExternal: Boolean get() = format.id?.contains("external:") == true
}
