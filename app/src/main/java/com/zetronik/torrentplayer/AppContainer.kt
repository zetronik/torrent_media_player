package com.zetronik.torrentplayer

import android.content.Context
import android.util.Log
import com.zetronik.torrentplayer.data.AppDatabase
import com.zetronik.torrentplayer.data.PlaybackPositionRepository
import com.zetronik.torrentplayer.data.RecentRepository
import com.zetronik.torrentplayer.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    /**
     * For work that must outlive a screen, e.g. dropping torrent data after the player closes.
     * A failure there is logged instead of taking the whole app down.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("AppContainer", "Background task failed", e) }
    )

    val torrentEngine = TorrentEngine(context)
    private val database = AppDatabase.build(context)
    val recentRepository = RecentRepository(database, torrentEngine)
    val positionRepository = PlaybackPositionRepository(database)
}
