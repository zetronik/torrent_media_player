package com.zetronik.torrentplayer.torrent

import android.system.ErrnoException
import android.system.Os
import android.util.Log
import org.libtorrent4j.Priority
import java.io.File
import java.util.BitSet

/** What is being streamed from a torrent. Outlives torrent generations (see [TorrentEngine.restartFrom]). */
class StreamConfig(
    val fileIndex: Int,
    val extraFiles: List<Int>,
    val budget: StreamBudget,
) {
    /** Media bytes per second, reported by the player once the duration is known; 0 until then. */
    @Volatile
    var bitrate: Long = 0

    /** Set when the filesystem cannot punch holes: disk usage is then bounded by restarting instead. */
    @Volatile
    var punchUnsupported = false

    /** Smoothed payload download rate, bytes per second. Refreshed by the engine's stream monitor. */
    @Volatile
    var downloadRate: Long = 0

    /** Contiguous bytes on disk ahead of the player's loader. Refreshed by the engine's stream monitor. */
    @Volatile
    var diskAheadBytes: Long = 0

    /** True while the player has stalled (buffering after it already played); arms the prebuffer gate. */
    @Volatile
    var playerStalled = false

    /**
     * Set by the monitor once the download rate has been sampled long enough to trust: right after
     * the start the rate is still ramping up and would demand an absurd prebuffer.
     */
    @Volatile
    var rateSettled = false

    /** Buffer the player should collect before (re)starting playback; see [PrebufferPolicy]. */
    fun requiredBufferMs(): Long =
        if (rateSettled) PrebufferPolicy.requiredMs(bitrate, downloadRate, budget.aheadBytes) else 0

    /** [diskAheadBytes] in media time, or 0 while the bitrate is unknown. */
    fun diskAheadMs(): Long = bitrate.takeIf { it > 0 }?.let { diskAheadBytes * 1000 / it } ?: 0
}

/**
 * Turns the torrent into a bounded ring cache around the player's read position:
 *  - only pieces in the read-ahead window are wanted (the rest of the file has priority 0),
 *    downloaded in order (sequential mode) with deadlines on the next few seconds;
 *  - pieces further behind than the "behind" budget are freed on disk.
 * Disk usage therefore stays near [StreamBudget.totalBytes] whatever the file size.
 */
class StreamPrioritizer(private val torrent: ActiveTorrent, private val config: StreamConfig) {

    private val info = checkNotNull(torrent.info) { "Metadata is not available yet" }
    private val storage = info.files()
    private val pieceLength = info.pieceLength().toLong()
    private val fileOffset = storage.fileOffset(config.fileIndex)
    private val fileSize = storage.fileSize(config.fileIndex)
    private val path = File(torrent.savePath, storage.filePath(config.fileIndex)).absolutePath

    val window = StreamWindow(
        firstPiece = pieceAt(0),
        lastPiece = pieceAt(maxOf(fileSize, 1) - 1),
        pieceLength = pieceLength,
        budget = config.budget,
    )

    /** Container index (MKV Cues, MP4 moov) usually lives at the end; kept for the whole session. */
    /** Container header (EBML header, segment info, track list); read first, before any seek. */
    private val head: IntRange = window.firstPiece..pieceAt(minOf(fileSize, HEAD_BYTES) - 1)

    private val tail: IntRange = run {
        val tailBytes = (fileSize / 100).coerceIn(TAIL_MIN_BYTES, TAIL_MAX_BYTES)
        maxOf(window.firstPiece, pieceAt(maxOf(0, fileSize - tailBytes)))..window.lastPiece
    }

    /** Reads of the header or index while the window is elsewhere: lookups, not playback moving there. */
    private fun isLookup(piece: Int): Boolean = (piece in head || piece in tail) &&
        (resumePending || (readPiece >= 0 && readPiece !in head && readPiece !in tail))

    /**
     * Resuming mid-film from an estimated byte offset: the window waits for the first real playback read.
     * The estimate (time share of the file size) is off by hundreds of MB on VBR remuxes, and an ordered
     * download aimed there is wasted once the demuxer seeks to the exact cluster from the index.
     */
    private var resumePending = false

    private var readPiece = -1
    private var wanted = IntRange.EMPTY
    /**
     * Pieces of the file that may hold data on disk: everything that was ever wanted and not freed since.
     * Freeing works from this set rather than a forward-moving cursor, so data downloaded before a seek
     * back, or partially downloaded pieces left behind by a jump, are freed too.
     */
    private val touched = BitSet()
    /** Freed before they completed: blocks still in flight may land later, so they are freed again then. */
    private val freedIncomplete = BitSet()
    /** Last piece that already got a deadline in the ordered range. */
    private var deadlineEnd = -1
    /** Bytes behind the read position that could not be freed (no hole punching on this filesystem). */
    private var unfreedBytes = 0L
    private var freedPieces = 0
    private var freedBytes = 0L

    fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLength).toInt()

    /**
     * Sets every piece priority from scratch and front-loads what is needed to start at [startPosition].
     * With an [exact] position (a reader asked for it) the window starts there; an estimate only fetches
     * the header and index until the demuxer reads the real resume point.
     */
    @Synchronized
    fun prepare(startPosition: Long, exact: Boolean) {
        val handle = torrent.handle
        val priorities = Priority.array(Priority.IGNORE, info.numPieces())
        for (extra in config.extraFiles) {
            for (p in storage.pieceIndexAtFile(extra)..storage.lastPieceIndexAtFile(extra)) {
                priorities[p] = Priority.TOP_PRIORITY
            }
        }
        for (p in head) priorities[p] = Priority.TOP_PRIORITY
        for (p in tail) priorities[p] = Priority.TOP_PRIORITY
        val start = pieceAt(startPosition)
        val deferWindow = !exact && start !in head
        if (!deferWindow) {
            for (p in window.wanted(start, config.bitrate)) {
                priorities[p] = Priority.DEFAULT
                touch(p)
            }
        }
        handle.prioritizePieces(priorities)

        readPiece = -1
        wanted = IntRange.EMPTY
        resumePending = deferWindow
        if (deferWindow) handle.clearPieceDeadlines() else onRead(start)
        for (p in head) {
            if (!torrent.hasPiece(p)) handle.setPieceDeadline(p, 0)
        }
        for ((i, p) in tail.withIndex()) {
            if (!torrent.hasPiece(p)) handle.setPieceDeadline(p, TAIL_DEADLINE_MS + i * 250)
        }
        for (extra in config.extraFiles) {
            for (p in storage.pieceIndexAtFile(extra)..storage.lastPieceIndexAtFile(extra)) {
                if (!torrent.hasPiece(p)) handle.setPieceDeadline(p, TAIL_DEADLINE_MS)
            }
        }
    }

    /**
     * Called by the reader for every read. Moves the window when the read position enters a new piece.
     * Returns `false` when the cache can only be bounded by restarting the torrent at this position.
     */
    @Synchronized
    fun onRead(piece: Int): Boolean {
        if (piece == readPiece) return true
        val handle = torrent.handle
        // Header and index lookups (MKV Cues, MP4 moov at the end) must not move the window: when resuming
        // mid-film the demuxer reads the header, then the index, then jumps to the resume point. Moving there
        // would aim the whole ordered download at the wrong part of the file (measured: ~50 s start).
        if (isLookup(piece)) {
            if (!torrent.hasPiece(piece)) handle.setPieceDeadline(piece, 0)
            return true
        }
        resumePending = false
        val bitrate = config.bitrate
        val critical = window.criticalPieces(bitrate)
        val jumped = readPiece == -1 || piece < readPiece || piece > readPiece + critical
        readPiece = piece

        val newWanted = window.wanted(piece, bitrate)
        for (p in wanted) {
            if (p !in newWanted && p !in tail && !torrent.hasPiece(p)) handle.piecePriority(p, Priority.IGNORE)
        }
        for (p in newWanted) {
            if (p !in wanted && !torrent.hasPiece(p)) handle.piecePriority(p, Priority.DEFAULT)
            touch(p)
        }
        wanted = newWanted

        // Deadlines make libtorrent download in order: its sequential mode does not hold up once only a
        // window of the file is wanted (measured on a TV: the buffer stayed at the 12 deadline pieces while
        // 10 MB/s went to random pieces further ahead). The next few pieces are re-tightened on every move,
        // pieces entering the ordered range get a deadline once.
        if (jumped) {
            handle.clearPieceDeadlines()
            deadlineEnd = piece - 1
        }
        val step = window.deadlineStepMs(bitrate)
        val criticalEnd = minOf(window.lastPiece, piece + critical - 1)
        for ((i, p) in (piece..criticalEnd).withIndex()) {
            if (!torrent.hasPiece(p)) handle.setPieceDeadline(p, i * step)
        }
        val orderedEnd = minOf(window.lastPiece, piece + window.orderedPieces(bitrate) - 1)
        for (p in maxOf(deadlineEnd + 1, criticalEnd + 1)..orderedEnd) {
            if (!torrent.hasPiece(p)) handle.setPieceDeadline(p, (p - piece) * step)
        }
        deadlineEnd = maxOf(deadlineEnd, orderedEnd)

        freeOutsideWindow(piece)
        return !config.punchUnsupported || unfreedBytes <= config.budget.totalBytes
    }

    /**
     * Prebuffer gate. While the player is stalled and the download barely keeps up with the bitrate,
     * data from [piece] on is held back until [StreamConfig.requiredBufferMs] worth of it is on disk;
     * otherwise every peak in the bitrate would stall playback again a few seconds later.
     */
    /** Longest the gate may hold playback: 1.5x the required buffer, at least 15 s. */
    fun prebufferTimeoutMs(): Long = maxOf(MIN_PREBUFFER_TIMEOUT_MS, config.requiredBufferMs() * 3 / 2)

    fun requiredBufferMs(): Long = config.requiredBufferMs()

    fun prebufferSatisfied(piece: Int): Boolean {
        if (!config.playerStalled) return true
        val required = config.requiredBufferMs()
        if (required <= 0) return true
        val neededBytes = required * config.bitrate / 1000
        val contiguous = torrent.contiguousPieces(piece, window.lastPiece)
        return piece + contiguous > window.lastPiece || contiguous * pieceLength >= neededBytes
    }

    /**
     * Called every second. The first missing piece after the read position caps the whole buffer, and a
     * slow peer can sit on it for many seconds while its deadline is still in the future (seen on the TV:
     * 19 pieces ready behind one gap for 20 s). A deadline of "now" makes libtorrent treat it as late and
     * fetch its remaining blocks from other peers too.
     */
    @Synchronized
    fun boostFrontier() {
        if (readPiece < 0) return
        val frontier = readPiece + torrent.contiguousPieces(readPiece, window.lastPiece)
        if (frontier in wanted) torrent.handle.setPieceDeadline(frontier, 0)
    }

    /** The gate gave up waiting: stay out of the way until the player reports a new stall. */
    fun disarmPrebuffer() {
        config.playerStalled = false
    }

    /** Diagnostic map of the next [count] pieces from the read position: `#` on disk, `.` missing. */
    @Synchronized
    fun haveMap(count: Int): String {
        if (readPiece < 0) return ""
        val end = minOf(window.lastPiece, readPiece + count - 1)
        return (readPiece..end).joinToString("") { if (torrent.hasPiece(it)) "#" else "." }
    }

    /** Bytes available on disk from the current read position onwards, without gaps. */
    @Synchronized
    fun bufferedAheadBytes(): Long {
        if (readPiece < 0) return 0
        return torrent.contiguousPieces(readPiece, window.lastPiece) * pieceLength
    }

    /** Read position in bytes from the start of the file, or -1 before the first playback read. */
    @Synchronized
    fun readPosition(): Long = if (readPiece < 0) -1 else maxOf(0, readPiece * pieceLength - fileOffset)

    /** Disk space the streamed file really occupies (allocated blocks, holes excluded). */
    fun allocatedBytes(): Long = try {
        Os.stat(path).st_blocks * 512
    } catch (_: ErrnoException) {
        0 // not created yet
    }

    // The header is tiny and re-read by some demuxers; the tail holds the index. Both stay for the session.
    private fun touch(piece: Int) {
        if (piece !in head && piece !in tail) touched.set(piece)
    }

    /**
     * Frees everything outside the ring: pieces further behind [piece] than the "behind" budget always,
     * leftovers ahead of the window (from before a seek back) when they push the cache over its budget.
     */
    private fun freeOutsideWindow(piece: Int) {
        if (!config.punchUnsupported) {
            // Pieces freed while incomplete that have completed since: their late blocks were written.
            var p = freedIncomplete.nextSetBit(0)
            while (p >= 0) {
                if (torrent.isDownloaded(p)) {
                    freedIncomplete.clear(p)
                    punch(p, p + 1)
                }
                p = freedIncomplete.nextSetBit(p + 1)
            }
        }
        freeTouched(window.firstPiece, window.discardBefore(piece))
        if (touched.cardinality() * pieceLength > config.budget.totalBytes) {
            freeTouched(wanted.last + 1, window.lastPiece + 1)
        }
    }

    /** Frees the touched pieces in `[from, until)`, one hole per contiguous run. */
    private fun freeTouched(from: Int, until: Int) {
        var start = touched.nextSetBit(maxOf(from, 0))
        while (start in 0 until until) {
            val end = minOf(touched.nextClearBit(start), until)
            touched.clear(start, end)
            if (config.punchUnsupported) {
                unfreedBytes += byteEnd(end) - byteStart(start)
            } else {
                for (p in start until end) {
                    // Marked before the blocks go, so no reader starts reading them meanwhile.
                    torrent.markDiscarded(p)
                    if (!torrent.isDownloaded(p)) freedIncomplete.set(p)
                }
                punch(start, end)
            }
            start = touched.nextSetBit(end)
        }
    }

    /** Punches a hole over pieces `[start, end)` of the file. */
    private fun punch(start: Int, end: Int) {
        val from = byteStart(start)
        val length = byteEnd(end) - from
        if (length <= 0) return
        val result = NativeFs.punchHole(path, from, length)
        if (result != 0) {
            Log.w(TAG, "Cannot free pieces $start..${end - 1} of $path: errno ${-result}; falling back to restarts")
            config.punchUnsupported = true
            unfreedBytes += length
        } else {
            freedBytes += length
            val before = freedPieces
            freedPieces += end - start
            if (freedPieces / LOG_EVERY_FREED != before / LOG_EVERY_FREED) {
                Log.i(TAG, "Freed $freedPieces pieces (${freedBytes shr 20} MB), reading piece $readPiece")
            }
        }
    }

    private fun byteStart(piece: Int): Long = maxOf(piece * pieceLength, fileOffset) - fileOffset

    private fun byteEnd(pieceExclusive: Int): Long =
        minOf(pieceExclusive * pieceLength, fileOffset + fileSize) - fileOffset

    private companion object {
        const val TAG = "StreamPrioritizer"
        const val MB = 1024L * 1024
        const val TAIL_MIN_BYTES = 2 * MB
        const val TAIL_MAX_BYTES = 10 * MB
        const val TAIL_DEADLINE_MS = 1_500
        const val HEAD_BYTES = 2 * MB
        const val LOG_EVERY_FREED = 16
        const val MIN_PREBUFFER_TIMEOUT_MS = 15_000L
    }
}
