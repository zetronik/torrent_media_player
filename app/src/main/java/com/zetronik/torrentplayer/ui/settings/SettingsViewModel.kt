package com.zetronik.torrentplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.torrent.StorageSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(container: AppContainer) : ViewModel() {
    private val settings = container.settings
    private val engine = container.torrentEngine

    val torrentCacheMb: StateFlow<Int> = settings.torrentCacheMb

    /** Re-read while the screen is shown: a stream paused under the settings may still be filling its cache. */
    val storage: StateFlow<StorageSpace?> = flow {
        while (true) {
            emit(engine.storageSpace())
            delay(STORAGE_REFRESH_MS)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTorrentCacheMb(mb: Int) = settings.setTorrentCacheMb(mb)

    private companion object {
        const val STORAGE_REFRESH_MS = 3_000L
    }
}
