package com.zetronik.torrentplayer.torrent

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.swig.alert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TorrentException(
    val reason: Reason,
    cause: Throwable? = null,
    /** For [Reason.INSUFFICIENT_SPACE]: free bytes on the data partition. */
    val freeBytes: Long = -1,
) : IOException(reason.name, cause) {
    enum class Reason { INVALID_INPUT, DOWNLOAD_FAILED, INVALID_TORRENT, ADD_FAILED, NOT_ACTIVE, INSUFFICIENT_SPACE }
}

/**
 * Owns the libtorrent session. Only one torrent is active at a time: adding a new one removes the previous
 * torrent together with its downloaded data.
 *
 * Structural operations (add / reset / close / prepare) are serialized with [mutex]; reads from the player
 * go straight to [ActiveTorrent] without touching it.
 */
class TorrentEngine(context: Context) {

    private val session = SessionManager(false)
    // Not in cacheDir: on low storage Android wipes app caches, which would pull files out from under
    // a running stream. The engine manages this directory's size itself (ring cache, wiped on start).
    private val dataRoot = File(context.noBackupFilesDir, "torrent-data")
    private val legacyDataRoot = File(context.cacheDir, "torrent-data")
    private val metadataDir = File(context.filesDir, "torrents")
    private val torrents = ConcurrentHashMap<String, ActiveTorrent>()
    /** Stream being played per torrent; re-applied when the torrent is restarted. */
    private val streams = ConcurrentHashMap<String, StreamConfig>()
    private val removalWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val generation = AtomicInteger()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is PieceFinishedAlert -> {
                    val handle = alert.handle()
                    torrents.values.firstOrNull { it.handle == handle }?.onPieceFinished(alert.pieceIndex())
                }
                is TorrentRemovedAlert -> {
                    removalWaiters[alert.getInfoHashes().getBest().toHex()]?.complete(Unit)
                }
            }
        }
    }

    /** Starts the session. Blocking; safe to call repeatedly. Leftover data from a previous run is wiped. */
    fun start() = synchronized(this) {
        if (started) return@synchronized
        dataRoot.deleteRecursively()
        legacyDataRoot.deleteRecursively()
        dataRoot.mkdirs()
        metadataDir.mkdirs()
        session.addListener(alertListener)
        // POSIX disk I/O instead of mmap: 32-bit TV boxes run out of address space mapping multi-GB files,
        // and pieces are on disk by the time they are reported as finished.
        session.start(SessionParams(buildSettings()).apply { setPosixDiskIO() })
        // SessionManager forces "everything except logs" on start; drop the chatty per-block categories.
        session.applySettings(
            SettingsPack().setInteger(settings_pack.int_types.alert_mask.swigValue(), ALERT_MASK)
        )
        started = true
    }

    fun metadataFile(infoHash: String): File = File(metadataDir, "$infoHash.torrent")

    /** True while [infoHash] is the torrent in the session (its file list or player is open). */
    fun isActive(infoHash: String): Boolean = torrents.containsKey(infoHash)

    /**
     * Stores `.torrent` [bytes] received from elsewhere (another device) as the saved metadata of [infoHash],
     * so a later [add] of its magnet link skips the metadata download. False if the bytes are not that torrent.
     */
    suspend fun importMetadata(infoHash: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val info = try {
            TorrentInfo(bytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Received metadata is not a torrent", e)
            return@withContext false
        }
        val actual = info.infoHashes().getBest().toHex()
        if (!actual.equals(infoHash, ignoreCase = true)) return@withContext false
        metadataDir.mkdirs()
        writeAtomically(metadataFile(actual), bytes)
        true
    }

    /** Adds the torrent to the session (or reuses it if it is already active) and returns its info hash. */
    suspend fun add(input: TorrentInput): String = withContext(Dispatchers.IO) {
        start()
        val request = resolve(input)
        mutex.withLock {
            if (torrents.containsKey(request.infoHash)) return@withLock request.infoHash
            for (other in torrents.keys.toList()) removeLocked(other)
            addLocked(request)
            request.infoHash
        }
    }

    /** Suspends until the torrent's metadata is known (immediate for `.torrent` files and saved torrents). */
    suspend fun awaitMetadata(infoHash: String): TorrentMeta = withContext(Dispatchers.IO) {
        var info: TorrentInfo? = null
        var torrent: ActiveTorrent
        var firstCheck = true
        do {
            if (!firstCheck) delay(METADATA_POLL_MS)
            firstCheck = false
            torrent = torrents[infoHash] ?: throw TorrentException(TorrentException.Reason.NOT_ACTIVE)
            info = torrent.info
            // status() is null for a handle that is being replaced (player exit re-adds the torrent);
            // the next iteration picks up the new generation.
            val status: TorrentStatus? = if (info == null && torrent.handle.isValid) torrent.handle.status() else null
            if (info == null && status?.hasMetadata() == true) {
                info = torrent.handle.torrentFile()
                torrent.attachMetadata(info)
                saveMetadata(torrent, info)
            }
        } while (info == null)
        torrent.toMeta(info)
    }

    fun stats(infoHash: String): Flow<TorrentStats> = flow {
        while (true) {
            emit(readStats(infoHash))
            delay(STATS_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Starts streaming [fileIndex] (plus its [extraFiles], e.g. subtitles, at top priority) from
     * [startPosition], with a ring cache sized from the free disk space.
     *
     * @throws TorrentException with [TorrentException.Reason.INSUFFICIENT_SPACE] when the device is too full.
     */
    suspend fun prepareStream(
        infoHash: String,
        fileIndex: Int,
        extraFiles: List<Int>,
        startPosition: Long,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val torrent = torrents[infoHash] ?: throw TorrentException(TorrentException.Reason.NOT_ACTIVE)
            val info = torrent.info ?: throw TorrentException(TorrentException.Reason.NOT_ACTIVE)
            // Plain usable space on purpose: counting other apps' clearable caches (getAllocatableBytes)
            // would make Android wipe them to make room for a temporary stream cache.
            @SuppressLint("UsableSpace")
            val free = dataRoot.usableSpace
            val budget = StreamBudget.forStream(info.files().fileSize(fileIndex), free)
                ?: throw TorrentException(TorrentException.Reason.INSUFFICIENT_SPACE, freeBytes = free)
            Log.i(TAG, "Stream budget: ahead ${budget.aheadBytes shr 20} MB, behind ${budget.behindBytes shr 20} MB")
            val config = StreamConfig(fileIndex, extraFiles, budget)
            streams[infoHash] = config
            applyStream(torrent, config, startPosition, exactStart = false)
            startMonitor(infoHash, config)
        }
    }

    /** Media bitrate reported by the player; sizes the read-ahead window in time rather than bytes. */
    fun setBitrate(infoHash: String, bytesPerSecond: Long) {
        streams[infoHash]?.bitrate = bytesPerSecond
    }

    /** The player stalled after it had been playing: arms the prebuffer gate (see [PrebufferPolicy]). */
    fun setPlayerStalled(infoHash: String, stalled: Boolean) {
        streams[infoHash]?.playerStalled = stalled
    }

    /** Opens a blocking reader for the player. Called on a loader thread. */
    fun openReader(infoHash: String, fileIndex: Int): TorrentReader {
        if (requireActive(infoHash).info == null) throw TorrentException(TorrentException.Reason.NOT_ACTIVE)
        return TorrentReader(this, infoHash, fileIndex)
    }

    internal fun activeOrNull(infoHash: String): ActiveTorrent? = torrents[infoHash]

    internal fun requireActive(infoHash: String): ActiveTorrent =
        torrents[infoHash] ?: throw TorrentException(TorrentException.Reason.NOT_ACTIVE)

    internal fun isStreamFile(infoHash: String, fileIndex: Int): Boolean = streams[infoHash]?.fileIndex == fileIndex

    /**
     * Re-adds the torrent as a fresh generation (empty cache) and restarts the stream window at [position].
     * Called from a player loader thread when it needs data that was already freed (a seek back), or when
     * the cache cannot be bounded otherwise. A no-op if [expected] was already replaced by another reader.
     */
    internal fun restartFrom(expected: ActiveTorrent, position: Long) {
        try {
            runBlocking(Dispatchers.IO) { restart(expected, position) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while restarting the torrent")
        }
    }

    private suspend fun restart(expected: ActiveTorrent, position: Long) = mutex.withLock {
        if (torrents[expected.infoHash] !== expected) return@withLock
        val config = streams[expected.infoHash]
        Log.i(TAG, "Restarting ${expected.infoHash} at byte $position")
        val fresh = regenerateLocked(expected) ?: return@withLock
        if (config != null) applyStream(fresh, config, position, exactStart = true)
    }

    /**
     * Throws away everything downloaded for the torrent but keeps it in the session with nothing selected,
     * so the file list stays available. Called when the player closes.
     */
    suspend fun resetData(infoHash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            streams.remove(infoHash)
            torrents[infoHash]?.let { regenerateLocked(it) }
        }
    }

    /** Removes the torrent and deletes its data. */
    suspend fun close(infoHash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            streams.remove(infoHash)
            removeLocked(infoHash)
        }
    }

    /**
     * Keeps [StreamConfig.downloadRate] and [StreamConfig.diskAheadBytes] fresh while the stream lives,
     * so the player's load control can read them on every iteration without calling into libtorrent.
     */
    @SuppressLint("UsableSpace") // free space is only logged
    private fun startMonitor(infoHash: String, config: StreamConfig) {
        scope.launch {
            var samples = 0
            while (streams[infoHash] === config) {
                val torrent = torrents[infoHash]
                val status: TorrentStatus? =
                    torrent?.handle?.takeIf { it.isValid }?.status(TorrentHandle.QUERY_PIECES)
                if (torrent != null && status != null) {
                    val rate = status.downloadPayloadRate().toLong()
                    val previous = config.downloadRate
                    config.downloadRate = if (previous == 0L) rate else (previous * 3 + rate) / 4
                    status.pieces()?.let { torrent.syncHave(it) }
                    config.diskAheadBytes = torrent.stream?.bufferedAheadBytes() ?: 0
                    torrent.stream?.boostFrontier()
                    if (++samples >= RATE_SETTLE_SAMPLES) config.rateSettled = true
                    if (samples % DISK_CHECK_EVERY == 0) torrent.stream?.let { enforceBudget(torrent, it, config) }
                    if (samples % MONITOR_LOG_EVERY == 0) {
                        Log.i(
                            TAG,
                            "Stream: rate ${config.downloadRate shr 10} KB/s, bitrate ${config.bitrate shr 10} KB/s, " +
                                "disk ahead ${config.diskAheadBytes shr 20} MB, required ${config.requiredBufferMs() / 1000} s, " +
                                "stalled ${config.playerStalled}, free ${dataRoot.usableSpace shr 20} MB, " +
                                "next [${torrent.stream?.haveMap(MONITOR_MAP_PIECES).orEmpty()}]",
                        )
                    }
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * Safety net for the ring cache: checks the blocks the streamed file really occupies on disk. If they
     * exceed the budget anyway (data the prioritizer could not free), the torrent is restarted at the read
     * position with an empty cache rather than letting the download fill the device.
     */
    private suspend fun enforceBudget(torrent: ActiveTorrent, stream: StreamPrioritizer, config: StreamConfig) {
        val position = stream.readPosition()
        if (position < 0) return
        val allocated = stream.allocatedBytes()
        val limit = config.budget.totalBytes * 5 / 4 + DISK_GUARD_SLACK_BYTES
        if (allocated <= limit) return
        Log.w(TAG, "Stream occupies ${allocated shr 20} MB, budget ${config.budget.totalBytes shr 20} MB: restarting")
        restart(torrent, position)
    }

    /** The active stream of [infoHash], for the player's load control. */
    fun streamConfig(infoHash: String): StreamConfig? = streams[infoHash]

    private fun applyStream(torrent: ActiveTorrent, config: StreamConfig, startPosition: Long, exactStart: Boolean) {
        val info = checkNotNull(torrent.info)
        // The streamed file keeps a non-zero file priority: libtorrent writes pieces of zero-priority files
        // into its part file instead of the real file the player reads. Which pieces are actually
        // downloaded is decided per piece by the prioritizer.
        val files = Priority.array(Priority.IGNORE, info.numFiles())
        files[config.fileIndex] = Priority.LOW
        for (extra in config.extraFiles) files[extra] = Priority.TOP_PRIORITY
        torrent.handle.prioritizeFiles(files)
        // In-order download inside the window keeps the buffer contiguous.
        torrent.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val prioritizer = StreamPrioritizer(torrent, config)
        torrent.stream = prioritizer
        prioritizer.prepare(startPosition, exactStart)
    }

    /** Removes [torrent] with its data and adds it again from metadata. Returns the new generation. */
    private suspend fun regenerateLocked(torrent: ActiveTorrent): ActiveTorrent? {
        val info = torrent.info ?: return null
        val trackers = torrent.handle.trackers().map { it.url() }
        // The TorrentInfo of a removed handle cannot be reused: libtorrent drops its file list
        // ("no files in torrent" on re-add). Re-add from an independent copy instead.
        val copy = loadSavedMetadata(torrent.infoHash) ?: TorrentInfo(encodeTorrent(info, trackers))
        removeLocked(torrent.infoHash)
        addLocked(AddRequest(torrent.infoHash, AddTorrentParams().apply { setTrackers(trackers) }, copy))
        return torrents[torrent.infoHash]
    }

    private class AddRequest(val infoHash: String, val params: AddTorrentParams, val info: TorrentInfo?)

    private fun resolve(input: TorrentInput): AddRequest = when (input) {
        is TorrentInput.Magnet -> {
            val params = try {
                AddTorrentParams.parseMagnetUri(input.uri)
            } catch (e: IllegalArgumentException) {
                throw TorrentException(TorrentException.Reason.INVALID_INPUT, e)
            }
            val infoHash = params.getInfoHashes().getBest().toHex()
            params.setTrackers((params.getTrackers() + PublicTrackers.urls).distinct())
            AddRequest(infoHash, params, loadSavedMetadata(infoHash))
        }
        is TorrentInput.TorrentFile -> fromTorrentBytes(
            runCatching { File(input.path).readBytes() }
                .getOrElse { throw TorrentException(TorrentException.Reason.INVALID_TORRENT, it) }
        )
        is TorrentInput.Url -> {
            val result = try {
                TorrentDownloader.download(input.url)
            } catch (e: IOException) {
                throw TorrentException(TorrentException.Reason.DOWNLOAD_FAILED, e)
            }
            when (result) {
                is TorrentDownloader.Result.Bytes -> fromTorrentBytes(result.data)
                is TorrentDownloader.Result.Magnet -> resolve(TorrentInput.Magnet(result.uri))
            }
        }
    }

    private fun fromTorrentBytes(bytes: ByteArray): AddRequest {
        val info = try {
            TorrentInfo(bytes)
        } catch (e: IllegalArgumentException) {
            throw TorrentException(TorrentException.Reason.INVALID_TORRENT, e)
        }
        val infoHash = info.infoHashes().getBest().toHex()
        writeAtomically(metadataFile(infoHash), bytes)
        return AddRequest(infoHash, AddTorrentParams(), info)
    }

    private fun loadSavedMetadata(infoHash: String): TorrentInfo? {
        val file = metadataFile(infoHash)
        if (!file.exists()) return null
        return try {
            TorrentInfo(file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Corrupted saved metadata for $infoHash", e)
            file.delete()
            null
        }
    }

    private fun addLocked(request: AddRequest) {
        val savePath = File(dataRoot, "${request.infoHash}/${generation.incrementAndGet()}")
        savePath.mkdirs()
        val params = request.params
        params.setSavePath(savePath.absolutePath)
        // Not auto-managed and not paused: the queue must never stop the torrent we are streaming.
        // Nothing is downloaded until the user picks a file.
        params.setFlags(
            TorrentFlags.UPDATE_SUBSCRIBE
                .or_(TorrentFlags.APPLY_IP_FILTER)
                .or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        )
        request.info?.let {
            params.setTorrentInfo(it)
            params.filePriorities(Priority.array(Priority.IGNORE, it.numFiles()))
        }
        val error = error_code()
        val handle = session.swig().add_torrent(params.swig(), error)
        if (error.value() != 0 || !handle.is_valid) {
            throw TorrentException(TorrentException.Reason.ADD_FAILED, IOException(error.message()))
        }
        val torrent = ActiveTorrent(request.infoHash, TorrentHandle(handle), savePath)
        request.info?.let { torrent.attachMetadata(it) }
        torrents[request.infoHash] = torrent
    }

    private suspend fun removeLocked(infoHash: String) {
        val torrent = torrents.remove(infoHash) ?: return
        torrent.close()
        val waiter = CompletableDeferred<Unit>()
        removalWaiters[infoHash] = waiter
        try {
            if (torrent.handle.isValid) {
                session.swig().remove_torrent(torrent.handle.swig(), SessionHandle.DELETE_FILES)
                // Re-adding the same info hash fails until libtorrent has really dropped the old handle.
                withTimeoutOrNull(REMOVE_TIMEOUT_MS) { waiter.await() }
            }
        } finally {
            removalWaiters.remove(infoHash)
        }
        torrent.savePath.deleteRecursively()
        torrent.savePath.parentFile?.delete() // only succeeds once no other generation of this torrent remains
    }

    private fun saveMetadata(torrent: ActiveTorrent, info: TorrentInfo) {
        try {
            val bytes = encodeTorrent(info, torrent.handle.trackers().map { it.url() })
            writeAtomically(metadataFile(torrent.infoHash), bytes)
        } catch (e: Exception) {
            // Only costs a slower re-open (metadata is fetched from peers again), so never fail the caller.
            Log.w(TAG, "Could not save metadata for ${torrent.infoHash}", e)
        }
    }

    /** Bencoded `.torrent` file for [info] with [trackers]. */
    private fun encodeTorrent(info: TorrentInfo, trackers: List<String>): ByteArray {
        val params = AddTorrentParams()
        params.setTorrentInfo(info)
        params.setTrackers(trackers)
        return Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readStats(infoHash: String): TorrentStats {
        val torrent = torrents[infoHash] ?: return TorrentStats.Empty
        if (!torrent.handle.isValid) return TorrentStats.Empty
        val status: TorrentStatus = torrent.handle.status() ?: return TorrentStats.Empty
        val connectedSeeds = status.numSeeds()
        val connectedPeers = (status.numPeers() - connectedSeeds).coerceAtLeast(0)
        val swarmSeeds = status.numComplete()
        val swarmPeers = status.numIncomplete()
        val stream = streams[infoHash]
        val buffered = torrent.stream?.bufferedAheadBytes() ?: 0
        return TorrentStats(
            bufferedBytes = buffered,
            bufferedMs = stream?.bitrate?.takeIf { it > 0 }?.let { buffered * 1000 / it } ?: -1,
            budgetBytes = stream?.budget?.aheadBytes ?: 0,
            requiredMs = stream?.requiredBufferMs() ?: 0,
            hasMetadata = status.hasMetadata(),
            downloadRate = status.downloadPayloadRate(),
            connectedSeeds = connectedSeeds,
            connectedPeers = connectedPeers,
            seeds = if (swarmSeeds >= 0) maxOf(swarmSeeds, connectedSeeds) else maxOf(status.listSeeds(), connectedSeeds),
            peers = if (swarmPeers >= 0) maxOf(swarmPeers, connectedPeers) else connectedPeers,
            dhtNodes = session.dhtNodes(),
            error = status.errorCode().takeIf { it.isError }?.message,
        )
    }

    private fun ActiveTorrent.toMeta(info: TorrentInfo): TorrentMeta {
        val storage = info.files()
        val files = (0 until storage.numFiles())
            .filterNot { storage.padFileAt(it) }
            .map { TorrentFileEntry(it, storage.filePath(it).replace('\\', '/'), storage.fileSize(it)) }
        return TorrentMeta(
            infoHash = infoHash,
            name = info.name(),
            totalSize = info.totalSize(),
            magnetUri = handle.makeMagnetUri(),
            files = files,
        )
    }

    private fun buildSettings(): SettingsPack = SettingsPack()
        // Downloading only: with zero unchoke slots no peer is ever allowed to request data from us.
        // Deliberately no upload rate limit: it also throttles protocol and TCP ACK overhead,
        // which starves the download itself (measured: ~1 KB/s instead of ~1 MB/s).
        .setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 0)
        .seedingOutgoingConnections(false)
        // Tuned for low-end TV boxes.
        .connectionsLimit(120)
        .activeDownloads(2)
        .activeSeeds(0)
        .setInteger(settings_pack.int_types.aio_threads.swigValue(), 2)
        .setInteger(settings_pack.int_types.hashing_threads.swigValue(), 1)
        // Let blocks be re-requested from another peer sooner, so one slow peer does not stall
        // the piece the player is waiting for.
        .setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), false)
        // When the read-ahead window is full the torrent is briefly "finished"; keep the seeds connected
        // so downloading resumes instantly as the window moves on.
        .setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)

    private companion object {
        const val TAG = "TorrentEngine"
        const val METADATA_POLL_MS = 300L
        const val STATS_INTERVAL_MS = 1_000L
        const val REMOVE_TIMEOUT_MS = 5_000L
        const val MONITOR_INTERVAL_MS = 1_000L
        const val RATE_SETTLE_SAMPLES = 15
        const val MONITOR_LOG_EVERY = 5
        const val MONITOR_MAP_PIECES = 40
        const val DISK_CHECK_EVERY = 5
        /** Head, tail, partially downloaded pieces: the file may exceed the budget by this much legitimately. */
        const val DISK_GUARD_SLACK_BYTES = 64L * 1024 * 1024

        val ALERT_MASK = alert.error_notification
            .or_(alert.status_notification)
            .or_(alert.storage_notification)
            .or_(alert.tracker_notification)
            .or_(alert.piece_progress_notification)
            .to_int()
    }
}
