package com.zetronik.torrentplayer.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.media.LocalKind
import com.zetronik.torrentplayer.media.LocalLibrary
import com.zetronik.torrentplayer.ui.navigation.FolderRoute
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** The "Files" tab: what the user added through the built-in browser. */
class FilesViewModel(private val container: AppContainer) : ViewModel() {

    /** [available] is false while the file is missing, e.g. on a USB drive that is not plugged in. */
    data class Row(
        val entry: LocalLibrary.Entry,
        val location: String,
        val size: Long,
        val available: Boolean,
    )

    /** [rows] is null until the first check finished, so the empty hint does not flash. */
    data class State(val hasPermission: Boolean, val rows: List<Row>?)

    sealed interface Event {
        data class Play(val route: PlayerRoute) : Event
        data class Open(val route: FolderRoute) : Event
        data object EmptyPlaylist : Event
        data object Unavailable : Event
    }

    private val files = container.localFiles
    private val library = container.localLibrary

    /** Bumped on resume: drives may have been plugged in or out, the permission granted. */
    private val checks = MutableStateFlow(0)

    val state: StateFlow<State> = combine(library.entries, checks) { entries, _ -> entries }
        .map { entries ->
            val granted = files.hasPermission()
            val volumes = if (granted) files.volumes() else emptyList()
            State(
                hasPermission = granted,
                rows = entries.map { entry ->
                    val file = File(entry.path)
                    val available = granted && if (entry.isFolder) file.isDirectory else file.isFile
                    Row(entry, files.location(entry.path, volumes), if (available && !entry.isFolder) file.length() else 0, available)
                },
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State(files.hasPermission(), null))

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun refresh() {
        checks.value++
    }

    fun open(row: Row) {
        if (!row.available) {
            _events.trySend(Event.Unavailable)
            return
        }
        val entry = row.entry
        when (entry.kind) {
            null -> _events.trySend(Event.Open(FolderRoute(entry.path, entry.name)))
            LocalKind.VIDEO -> _events.trySend(Event.Play(PlayerRoute(uri = Uri.fromFile(File(entry.path)).toString(), title = entry.name)))
            LocalKind.PLAYLIST -> {
                if (_busy.value) return
                _busy.value = true
                viewModelScope.launch {
                    try {
                        val videos = container.playlistResolver.resolve(File(entry.path))
                        _events.send(playlistPlayerRoute(videos)?.let { Event.Play(it) } ?: Event.EmptyPlaylist)
                    } finally {
                        _busy.value = false
                    }
                }
            }
        }
    }

    fun remove(row: Row) = library.remove(row.entry.path)
}
