package com.zetronik.torrentplayer.ui.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.data.PlaybackPosition
import com.zetronik.torrentplayer.media.FolderItem
import com.zetronik.torrentplayer.media.LocalKind
import com.zetronik.torrentplayer.ui.navigation.FolderRoute
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class FolderViewModel(
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(val items: List<FolderItem>) : State
        /** The drive was removed, or the permission revoked. */
        data object Unavailable : State
    }

    sealed interface Event {
        data class Play(val route: PlayerRoute) : Event
        data object EmptyPlaylist : Event
    }

    private val route = savedStateHandle.toRoute<FolderRoute>()
    val title: String = route.title

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** A playlist is being read. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Saved positions of the videos shown, by URI. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val positions: StateFlow<Map<String, PlaybackPosition>> = _state
        .map { state -> (state as? State.Ready)?.items.orEmpty().videoUris() }
        .distinctUntilChanged()
        .flatMapLatest { uris ->
            if (uris.isEmpty()) flowOf(emptyMap()) else container.positionRepository.observe(uris).map { list -> list.associateBy { it.key } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var loadJob: Job? = null

    /** Called on every resume: files may have been added, or the USB drive pulled out. */
    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val items = container.localFiles.list(route.path)
            _state.value = if (items == null) State.Unavailable else State.Ready(items)
        }
    }

    fun open(file: FolderItem.File) {
        val items = (_state.value as? State.Ready)?.items ?: return
        when (file.kind) {
            LocalKind.VIDEO -> _events.trySend(Event.Play(folderPlayerRoute(items.filterIsInstance<FolderItem.File>(), file)))
            LocalKind.PLAYLIST -> {
                if (_busy.value) return
                _busy.value = true
                viewModelScope.launch {
                    try {
                        val videos = container.playlistResolver.resolve(File(file.path))
                        _events.send(playlistPlayerRoute(videos)?.let { Event.Play(it) } ?: Event.EmptyPlaylist)
                    } finally {
                        _busy.value = false
                    }
                }
            }
        }
    }

    private companion object {
        /** SQLite's bound-parameter limit is 999 on older Android versions. */
        const val MAX_POSITION_KEYS = 900

        fun List<FolderItem>.videoUris(): List<String> =
            filterIsInstance<FolderItem.File>().filter { it.kind == LocalKind.VIDEO }.map { it.uri }.take(MAX_POSITION_KEYS)
    }
}
