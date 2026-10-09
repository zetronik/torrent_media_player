package com.zetronik.torrentplayer.data

import com.zetronik.torrentplayer.torrent.PublicTrackers
import com.zetronik.torrentplayer.torrent.TorrentEngine
import com.zetronik.torrentplayer.torrent.TorrentMeta
import com.zetronik.torrentplayer.torrent.TorrentStats
import com.zetronik.torrentplayer.torrent.TrackerScraper
import com.zetronik.torrentplayer.torrent.magnetTrackers
import kotlinx.coroutines.flow.Flow

class RecentRepository(
    private val db: AppDatabase,
    private val engine: TorrentEngine,
) {
    private val dao = db.recentTorrents()

    val recent: Flow<List<RecentTorrent>> = dao.observeAll()

    /** Moves the torrent to the top of the list and trims the list to [MAX_ITEMS]. */
    suspend fun recordOpened(meta: TorrentMeta, stats: TorrentStats) {
        val previous = dao.get(meta.infoHash)
        dao.upsert(
            RecentTorrent(
                infoHash = meta.infoHash,
                name = meta.name,
                totalSize = meta.totalSize,
                magnetUri = meta.magnetUri,
                seeds = if (stats.seeds > 0) stats.seeds else previous?.seeds ?: 0,
                peers = if (stats.peers > 0) stats.peers else previous?.peers ?: 0,
                lastOpenedAt = System.currentTimeMillis(),
            )
        )
        dao.hashesBeyond(MAX_ITEMS).forEach { remove(it) }
    }

    suspend fun updateSwarm(infoHash: String, seeds: Int, peers: Int) = dao.updateSwarm(infoHash, seeds, peers)

    suspend fun remove(infoHash: String) {
        dao.delete(infoHash)
        db.playbackPositions().deleteForTorrent(infoHash)
        engine.metadataFile(infoHash).delete()
    }

    /** Empties the list, with saved metadata and playback positions. */
    suspend fun clear() {
        dao.allHashes().forEach { remove(it) }
    }

    /**
     * Asks the trackers of [torrents] (from their magnet links, plus the public ones) for current seed and
     * peer counts and stores the answers. Returns how many torrents got an answer.
     */
    suspend fun refreshSwarm(torrents: List<RecentTorrent>): Int {
        val trackers = torrents.associate { torrent ->
            torrent.infoHash to (magnetTrackers(torrent.magnetUri) + PublicTrackers.urls).distinct()
        }
        val answers = TrackerScraper.scrape(trackers)
        for ((infoHash, swarm) in answers) dao.updateSwarm(infoHash, swarm.seeds, swarm.peers)
        return answers.size
    }

    companion object {
        const val MAX_ITEMS = 20
    }
}

class PlaybackPositionRepository(db: AppDatabase) {
    private val dao = db.playbackPositions()

    suspend fun get(key: String): PlaybackPosition? = dao.get(key)

    fun observeForTorrent(infoHash: String): Flow<List<PlaybackPosition>> = dao.observeForTorrent(infoHash)

    /** Positions of local files, keyed by their URIs. */
    fun observe(keys: List<String>): Flow<List<PlaybackPosition>> = dao.observe(keys)

    /** Watched to the end (or barely started) means there is nothing to resume. */
    suspend fun save(key: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && durationMs - positionMs < END_THRESHOLD_MS
        if (positionMs < START_THRESHOLD_MS || finished) {
            dao.delete(key)
        } else {
            dao.upsert(PlaybackPosition(key, positionMs, durationMs, System.currentTimeMillis()))
        }
    }

    companion object {
        private const val START_THRESHOLD_MS = 30_000L
        private const val END_THRESHOLD_MS = 60_000L

        fun torrentKey(infoHash: String, fileIndex: Int) = "$infoHash:$fileIndex"
    }
}
