package com.zetronik.torrentplayer.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zetronik.torrentplayer.data.RecentRepository
import com.zetronik.torrentplayer.data.RecentTorrent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecentViewModel(private val repository: RecentRepository) : ViewModel() {

    /** Outcome of a seed/peer refresh: how many of the requested torrents got an answer. */
    data class RefreshResult(val updated: Int, val requested: Int)

    /** `null` until the database answered, so the empty state does not flash on start. */
    val items: StateFlow<List<RecentTorrent>?> = repository.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _refreshResults = Channel<RefreshResult>(Channel.BUFFERED)
    val refreshResults: Flow<RefreshResult> = _refreshResults.receiveAsFlow()

    fun delete(infoHash: String) {
        viewModelScope.launch { repository.remove(infoHash) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clear() }
    }

    fun refreshAll() = refresh(items.value.orEmpty())

    fun refresh(torrent: RecentTorrent) = refresh(listOf(torrent))

    private fun refresh(torrents: List<RecentTorrent>) {
        // One refresh at a time; further presses while it runs are ignored.
        if (torrents.isEmpty() || _refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val updated = repository.refreshSwarm(torrents)
                _refreshResults.send(RefreshResult(updated, torrents.size))
            } finally {
                _refreshing.value = false
            }
        }
    }
}
