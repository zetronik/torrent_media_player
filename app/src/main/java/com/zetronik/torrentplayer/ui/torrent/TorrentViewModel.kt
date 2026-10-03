package com.zetronik.torrentplayer.ui.torrent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.data.PlaybackPosition
import com.zetronik.torrentplayer.data.PlaybackPositionRepository
import com.zetronik.torrentplayer.torrent.TorrentException
import com.zetronik.torrentplayer.torrent.TorrentInput
import com.zetronik.torrentplayer.torrent.TorrentMeta
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.ui.navigation.TorrentRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TorrentViewModel(
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    sealed interface State {
        data object Resolving : State
        data object FetchingMetadata : State
        data class Ready(val meta: TorrentMeta) : State
        data class Failed(val reason: TorrentException.Reason?, val message: String?) : State
    }

    private val source = savedStateHandle.toRoute<TorrentRoute>().source
    private val engine = container.torrentEngine

    private val _state = MutableStateFlow<State>(State.FetchingMetadata)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TorrentStats.Empty)
    val stats: StateFlow<TorrentStats> = _stats.asStateFlow()

    private val _positions = MutableStateFlow<Map<Int, PlaybackPosition>>(emptyMap())
    val positions: StateFlow<Map<Int, PlaybackPosition>> = _positions.asStateFlow()

    private var infoHash: String? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val input = TorrentInput.parse(source)
            if (input == null) {
                _state.value = State.Failed(TorrentException.Reason.INVALID_INPUT, null)
                return@launch
            }
            _state.value = if (input is TorrentInput.Url) State.Resolving else State.FetchingMetadata
            try {
                val hash = engine.add(input)
                infoHash = hash
                launch { engine.stats(hash).collect { _stats.value = it } }
                launch {
                    container.positionRepository.observeForTorrent(hash).collect { list ->
                        _positions.value = list.associateBy { it.key.substringAfterLast(':').toInt() }
                    }
                }
                _state.value = State.FetchingMetadata
                val meta = engine.awaitMetadata(hash)
                _state.value = State.Ready(meta)
                container.recentRepository.recordOpened(meta, _stats.value)
                launch { persistSwarmSize(hash) }
            } catch (e: TorrentException) {
                _state.value = State.Failed(e.reason, e.cause?.message)
            }
        }
    }

    /** Trackers answer a few seconds after the torrent is added; keep the recent list's numbers fresh. */
    private suspend fun persistSwarmSize(hash: String) {
        var saved = -1 to -1
        _stats.collect { stats ->
            val current = stats.seeds to stats.peers
            if (current != saved && (stats.seeds > 0 || stats.peers > 0)) {
                saved = current
                container.recentRepository.updateSwarm(hash, stats.seeds, stats.peers)
            }
        }
    }

    override fun onCleared() {
        // Leaving the torrent screen drops the torrent and everything downloaded for it.
        infoHash?.let { hash -> container.appScope.launch { engine.close(hash) } }
    }
}
