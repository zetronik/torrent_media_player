package com.zetronik.torrentplayer.ui.player

import android.content.Context
import androidx.annotation.OptIn
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
import com.zetronik.torrentplayer.torrent.SubtitleMatcher
import com.zetronik.torrentplayer.torrent.TorrentException
import com.zetronik.torrentplayer.torrent.TorrentInput
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.torrent.TorrentUris
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
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

@OptIn(UnstableApi::class)
class PlayerViewModel(
    context: Context,
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    data class UiState(
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
    )

    data class Progress(val positionMs: Long, val durationMs: Long, val bufferedMs: Long)

    val route = savedStateHandle.toRoute<PlayerRoute>()
    private val isTorrent = route.infoHash != null
    private val engine = container.torrentEngine
    private val positionKey = if (isTorrent) {
        PlaybackPositionRepository.torrentKey(checkNotNull(route.infoHash), route.fileIndex)
    } else {
        route.uri
    }

    val player: ExoPlayer = PlayerFactory.create(context.applicationContext, engine, forTorrent = isTorrent)

    private val _ui = MutableStateFlow(UiState())
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
    /** Size of the streamed torrent file, used to derive its bitrate once the duration is known. */
    private var videoSize = 0L
    /** Becomes true once playback was ready; buffering after that is a stall, not the initial load. */
    private var hasPlayed = false

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
        viewModelScope.launch { prepare() }
        viewModelScope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) savePosition()
            }
        }
    }

    private suspend fun prepare() {
        val saved = container.positionRepository.get(positionKey)
        val startMs = saved?.positionMs ?: 0L
        val mediaItem = try {
            if (isTorrent) buildTorrentItem(startMs, saved?.durationMs ?: 0L) else MediaItem.fromUri(route.uri)
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

    private suspend fun buildTorrentItem(startMs: Long, durationMs: Long): MediaItem {
        val infoHash = checkNotNull(route.infoHash)
        // A no-op while the torrent screen keeps it active; re-adds it from saved metadata after process death.
        engine.add(TorrentInput.Magnet("magnet:?xt=urn:btih:$infoHash"))
        val meta = engine.awaitMetadata(infoHash)
        val video = meta.files.first { it.index == route.fileIndex }
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
        container.appScope.launch { container.positionRepository.save(positionKey, position, duration) }
    }

    override fun onCleared() {
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

        fun subtitleMimeType(extension: String): String = when (extension) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

data class TrackOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val format: Format,
    val selected: Boolean,
)
