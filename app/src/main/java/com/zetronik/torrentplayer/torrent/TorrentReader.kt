package com.zetronik.torrentplayer.torrent

import android.os.SystemClock
import android.util.Log
import org.libtorrent4j.Priority
import java.io.Closeable
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Random-access reader over one file of a torrent that is still downloading.
 *
 * The torrent behind it may be replaced by a newer generation (a seek back into already freed data
 * restarts the torrent), so the current [ActiveTorrent] is looked up on every read.
 * Not thread-safe: each player data source owns its own reader.
 */
class TorrentReader(
    private val engine: TorrentEngine,
    private val infoHash: String,
    private val fileIndex: Int,
) : Closeable {

    private var torrent: ActiveTorrent = engine.requireActive(infoHash)
    private val info = checkNotNull(torrent.info)
    private val pieceLength = info.pieceLength().toLong()
    private val fileOffset = info.files().fileOffset(fileIndex)
    private val relativePath = info.files().filePath(fileIndex)
    private var raf: RandomAccessFile? = null
    private var requestedPiece = -1

    val size: Long = info.files().fileSize(fileIndex)

    /** Reads up to [length] bytes at [position], blocking until the data is downloaded. Returns -1 at end of file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val absolute = fileOffset + position
        val piece = (absolute / pieceLength).toInt()
        while (true) {
            val current = currentTorrent()
            val stream = current.stream?.takeIf { engine.isStreamFile(infoHash, fileIndex) }
            val withinBudget = stream?.onRead(piece) ?: requestOnce(current, piece)
            if (!withinBudget || current.isDiscarded(piece)) {
                // The data was freed (seek back) or the disk budget can only be kept by starting over.
                engine.restartFrom(current, position)
                continue
            }
            try {
                current.awaitPiece(piece)
            } catch (e: TorrentClosedException) {
                if (engine.activeOrNull(infoHash) != null) continue else throw e
            }
            if (stream != null && !stream.prebufferSatisfied(piece)) waitForPrebuffer(current, stream, piece)

            val pieceEnd = (piece + 1) * pieceLength
            val count = minOf(length.toLong(), pieceEnd - absolute, size - position).toInt()
            val f = raf ?: RandomAccessFile(File(current.savePath, relativePath), "r").also { raf = it }
            f.seek(position)
            val read = f.read(buffer, offset, count)
            // Freed while we were reading: the bytes may be zeros, read again after the restart.
            if (current.isDiscarded(piece)) continue
            return read
        }
    }

    /**
     * The player's loader waits here while the prebuffer collects on disk. It counts as "loading", so
     * ExoPlayer neither gives up as stuck nor pulls the whole prebuffer into RAM. The wait is bounded:
     * if the network cannot deliver in reasonable time, playing on beats an endless spinner.
     */
    private fun waitForPrebuffer(current: ActiveTorrent, stream: StreamPrioritizer, piece: Int) {
        val started = SystemClock.elapsedRealtime()
        val timeout = stream.prebufferTimeoutMs()
        Log.i(TAG, "Prebuffer: holding piece $piece for ${stream.requiredBufferMs() / 1000} s of buffer")
        var waited = 0L
        while (!current.closed && !stream.prebufferSatisfied(piece)) {
            waited = SystemClock.elapsedRealtime() - started
            if (waited >= timeout) {
                Log.i(TAG, "Prebuffer: gave up after ${waited / 1000} s")
                stream.disarmPrebuffer()
                return
            }
            try {
                Thread.sleep(PREBUFFER_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while prebuffering")
            }
        }
        Log.i(TAG, "Prebuffer: released after ${waited / 1000} s")
    }

    /** Files other than the streamed one (e.g. subtitles) are simply fetched on demand. */
    private fun requestOnce(current: ActiveTorrent, piece: Int): Boolean {
        if (piece != requestedPiece && !current.hasPiece(piece)) {
            current.handle.piecePriority(piece, Priority.TOP_PRIORITY)
            current.handle.setPieceDeadline(piece, 0)
        }
        requestedPiece = piece
        return true
    }

    private fun currentTorrent(): ActiveTorrent {
        val current = engine.requireActive(infoHash)
        if (current !== torrent) {
            raf?.close()
            raf = null
            requestedPiece = -1
            torrent = current
        }
        return current
    }

    override fun close() {
        raf?.close()
        raf = null
    }

    private companion object {
        const val TAG = "TorrentReader"
        const val PREBUFFER_POLL_MS = 250L
    }
}
