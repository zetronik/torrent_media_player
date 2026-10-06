package com.zetronik.torrentplayer.ui.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.remote.RemoteAction
import com.zetronik.torrentplayer.remote.RemoteClient
import com.zetronik.torrentplayer.remote.RemoteStatus
import com.zetronik.torrentplayer.torrent.TorrentUris
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import com.zetronik.torrentplayer.ui.navigation.RemoteRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** The phone as a remote control: polls the TV's player state and sends commands. */
class RemoteViewModel(savedStateHandle: SavedStateHandle, container: AppContainer) : ViewModel() {

    data class UiState(
        /** Null until the TV answered once. */
        val status: RemoteStatus? = null,
        val connectionLost: Boolean = false,
        /** Seek bar position while it is being dragged. */
        val pendingSeekMs: Long? = null,
    )

    val route = savedStateHandle.toRoute<RemoteRoute>()
    private val client = RemoteClient(route.host, route.port, container.remotePairings.token(route.deviceId))

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var failures = 0

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refresh() {
        try {
            val status = client.status()
            failures = 0
            _ui.update { it.copy(status = status, connectionLost = false) }
        } catch (_: IOException) {
            if (++failures >= MAX_FAILURES) _ui.update { it.copy(connectionLost = true) }
        }
    }

    fun command(action: String, value: Long = 0) {
        viewModelScope.launch {
            try {
                client.control(action, value)
                refresh()
            } catch (_: IOException) {
                // The next poll reports a lost connection.
            }
        }
    }

    fun seekTo(positionMs: Long, commit: Boolean) {
        _ui.update { it.copy(pendingSeekMs = positionMs) }
        if (!commit) return
        viewModelScope.launch {
            try {
                client.control(RemoteAction.SEEK_TO, positionMs)
                refresh()
            } catch (_: IOException) {
            }
            _ui.update { it.copy(pendingSeekMs = null) }
        }
    }

    /** Closes the player on the TV; [onDone] runs either way. */
    fun stopOnTv(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { client.control(RemoteAction.STOP) }
            onDone()
        }
    }

    /** Stops the TV and hands the current file and position back to the phone's player. */
    fun continueHere(onReady: (PlayerRoute) -> Unit) {
        val status = _ui.value.status?.takeIf { it.active } ?: return
        val infoHash = status.infoHash ?: return
        viewModelScope.launch {
            runCatching { client.control(RemoteAction.STOP) }
            onReady(
                PlayerRoute(
                    uri = TorrentUris.build(infoHash, status.fileIndex).toString(),
                    title = status.title,
                    infoHash = infoHash,
                    fileIndex = status.fileIndex,
                    startPositionMs = status.positionMs,
                    startDurationMs = status.durationMs,
                )
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_FAILURES = 3
    }
}
