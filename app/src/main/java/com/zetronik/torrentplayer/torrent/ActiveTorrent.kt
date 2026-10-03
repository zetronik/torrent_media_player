package com.zetronik.torrentplayer.torrent

import org.libtorrent4j.PieceIndexBitfield
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import java.io.File
import java.io.InterruptedIOException
import java.io.IOException
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thrown to readers waiting on a torrent that was removed or replaced by a newer generation. */
class TorrentClosedException : IOException("Torrent closed")

/**
 * One torrent added to the session. A new instance (a new "generation" with its own save path) is created
 * every time the torrent is (re-)added, so the piece bitmaps always describe the current handle.
 */
class ActiveTorrent(
    val infoHash: String,
    val handle: TorrentHandle,
    val savePath: File,
) {
    @Volatile
    var info: TorrentInfo? = null
        private set

    /** Window manager of the file being played, set by [TorrentEngine.prepareStream]. */
    @Volatile
    var stream: StreamPrioritizer? = null

    private val lock = ReentrantLock()
    private val pieceArrived = lock.newCondition()
    private val havePieces = BitSet()
    /** Downloaded pieces whose disk blocks were freed. libtorrent still thinks it has them. */
    private val discardedPieces = BitSet()

    @Volatile
    var closed = false
        private set

    fun attachMetadata(torrentInfo: TorrentInfo) {
        val status: TorrentStatus? = handle.status(TorrentHandle.QUERY_PIECES)
        val bitfield = status?.pieces()
        lock.withLock {
            info = torrentInfo
            if (bitfield != null) for (i in 0 until bitfield.size()) if (bitfield.getBit(i)) havePieces.set(i)
        }
    }

    fun onPieceFinished(piece: Int) {
        lock.withLock {
            havePieces.set(piece)
            pieceArrived.signalAll()
        }
    }

    /** Merges libtorrent's own piece bitmap, in case a piece-finished alert was dropped. */
    fun syncHave(bitfield: PieceIndexBitfield) {
        val size = bitfield.size()
        val have = BitSet(size)
        for (i in 0 until size) if (bitfield.getBit(i)) have.set(i)
        lock.withLock {
            val before = havePieces.cardinality()
            havePieces.or(have)
            if (havePieces.cardinality() != before) pieceArrived.signalAll()
        }
    }

    /** Downloaded and still on disk. */
    fun hasPiece(piece: Int): Boolean = lock.withLock { havePieces.get(piece) && !discardedPieces.get(piece) }

    /** Downloaded at some point, whether or not it was freed since. */
    fun isDownloaded(piece: Int): Boolean = lock.withLock { havePieces.get(piece) }

    fun isDiscarded(piece: Int): Boolean = lock.withLock { discardedPieces.get(piece) }

    /** Marks a piece as gone *before* its blocks are freed, so no reader starts reading it meanwhile. */
    fun markDiscarded(piece: Int) = lock.withLock { discardedPieces.set(piece) }

    /** Number of consecutive pieces available on disk starting at [piece]. */
    fun contiguousPieces(piece: Int, lastPiece: Int): Int = lock.withLock {
        var p = piece
        while (p <= lastPiece && havePieces.get(p) && !discardedPieces.get(p)) p++
        p - piece
    }

    /**
     * Blocks the calling (player loader) thread until [piece] is downloaded and verified.
     * Thread interruption — how ExoPlayer cancels a load — ends the wait with [InterruptedIOException].
     */
    fun awaitPiece(piece: Int) {
        while (true) {
            lock.withLock {
                if (havePieces.get(piece)) return
                if (closed) throw TorrentClosedException()
                try {
                    pieceArrived.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while waiting for piece $piece")
                }
                if (havePieces.get(piece)) return
            }
            // Safety net in case an alert was dropped from the queue. Queried outside the lock:
            // it is a synchronous call into the libtorrent network thread.
            if (handle.isValid && handle.havePiece(piece)) {
                lock.withLock { havePieces.set(piece) }
                return
            }
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            pieceArrived.signalAll()
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
