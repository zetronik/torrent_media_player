package com.zetronik.torrentplayer.torrent

/**
 * How much disk the stream may use. The cache is a ring: [aheadBytes] are downloaded in front of the
 * read position, [behindBytes] are kept behind it for short seeks back, everything older is freed.
 */
data class StreamBudget(val aheadBytes: Long, val behindBytes: Long) {
    val totalBytes: Long get() = aheadBytes + behindBytes

    companion object {
        private const val MB = 1024L * 1024
        private const val MIN_TOTAL = 96 * MB
        /** Never push the device into its low-storage state: Android starts wiping app caches there. */
        private const val RESERVE = 256 * MB
        private const val MAX_BEHIND = 256 * MB

        /** The cache holds about this much video, at a bitrate estimated from the file size. */
        private const val CACHE_SECONDS = 5 * 60L
        /** Short on purpose: a shorter assumed duration means a higher estimated bitrate, more cache. */
        private const val ASSUMED_DURATION_SECONDS = 90 * 60L
        private const val MIN_CACHE = 128 * MB
        private const val MAX_CACHE = 1536 * MB

        /**
         * Budget for streaming a file of [fileSize] bytes: a few minutes of video, not a share of the disk.
         * It is further limited by [freeBytes]. Returns `null` when there is not enough room to stream at all.
         */
        fun forStream(fileSize: Long, freeBytes: Long): StreamBudget? {
            val spaceCap = minOf(freeBytes * 6 / 10, freeBytes - RESERVE)
            if (spaceCap < MIN_TOTAL) return null
            val estimatedBitrate = fileSize / ASSUMED_DURATION_SECONDS
            val wanted = (estimatedBitrate * CACHE_SECONDS).coerceIn(MIN_CACHE, MAX_CACHE)
            val total = minOf(wanted, spaceCap)
            val behind = minOf(total * 15 / 100, MAX_BEHIND)
            return StreamBudget(aheadBytes = total - behind, behindBytes = behind)
        }

        /** Free space needed before playback can start; shown to the user when it is missing. */
        val minimumFreeBytes: Long = maxOf(MIN_TOTAL * 10 / 6, MIN_TOTAL + RESERVE)
    }
}

/**
 * How much must be buffered before playback starts or resumes after a stall.
 *
 * When the download is clearly faster than the media bitrate the player's usual few seconds are enough.
 * The closer the download gets to the bitrate (4K remuxes on a 100 Mbit line), the more is collected
 * up front, so peaks in the bitrate are absorbed instead of stalling every minute. The amount is capped
 * by what the disk budget can hold; at most 10 s + 1.5 × 40 s = 70 s.
 */
object PrebufferPolicy {
    private const val COMFORTABLE_RATIO = 1.5
    private const val BASE_MS = 10_000.0
    private const val PER_DEFICIT_MS = 40_000.0

    /** Required buffer in ms, or 0 to keep the player's default behaviour. */
    fun requiredMs(bitrate: Long, downloadRate: Long, aheadBudgetBytes: Long): Long {
        // Unknown yet (no duration, or the download has not ramped up): keep the fast default start.
        if (bitrate <= 0 || downloadRate <= 0) return 0
        val ratio = downloadRate.toDouble() / bitrate
        if (ratio >= COMFORTABLE_RATIO) return 0
        val wanted = BASE_MS + (COMFORTABLE_RATIO - ratio) * PER_DEFICIT_MS
        val diskCap = aheadBudgetBytes * 0.8 * 1000 / bitrate
        return minOf(wanted, diskCap).toLong()
    }
}

/**
 * Piece arithmetic for the file being played. Pure: no libtorrent calls, so it is unit-tested directly.
 *
 * [bitrate] (bytes per second of media) sizes the windows dynamically: high-bitrate remuxes get a
 * read-ahead limited by the disk budget, small files do not download far more than will be watched soon.
 * It is 0 until the player knows the duration.
 */
class StreamWindow(
    val firstPiece: Int,
    val lastPiece: Int,
    private val pieceLength: Long,
    private val budget: StreamBudget,
) {
    fun aheadPieces(bitrate: Long): Int {
        var bytes = budget.aheadBytes
        if (bitrate > 0) bytes = minOf(bytes, bitrate * MAX_AHEAD_SECONDS)
        return maxOf(MIN_AHEAD_PIECES, (bytes / pieceLength).toInt())
    }

    fun behindPieces(): Int = maxOf(1, (budget.behindBytes / pieceLength).toInt())

    /** Pieces that get deadlines: what playback needs within the next few seconds. */
    fun criticalPieces(bitrate: Long): Int {
        val bytes = maxOf(MIN_CRITICAL_BYTES, bitrate * CRITICAL_SECONDS)
        val pieces = ((bytes + pieceLength - 1) / pieceLength).toInt()
        return pieces.coerceIn(MIN_CRITICAL_PIECES, aheadPieces(bitrate))
    }

    /** Pieces that get increasing deadlines, so they arrive in order: about two minutes of video. */
    fun orderedPieces(bitrate: Long): Int {
        val bytes = if (bitrate > 0) bitrate * ORDERED_SECONDS else ORDERED_UNKNOWN_BYTES
        val pieces = ((bytes + pieceLength - 1) / pieceLength).toInt().coerceAtMost(MAX_ORDERED_PIECES)
        return maxOf(pieces, criticalPieces(bitrate)).coerceAtMost(aheadPieces(bitrate))
    }

    /** Pieces to download for a read at [readPiece]. */
    fun wanted(readPiece: Int, bitrate: Long): IntRange =
        readPiece..minOf(lastPiece, readPiece + aheadPieces(bitrate) - 1)

    /** Pieces before this index (exclusive) may be freed. */
    fun discardBefore(readPiece: Int): Int = readPiece - behindPieces()

    /** Spacing of piece deadlines: roughly when playback reaches the next piece. */
    fun deadlineStepMs(bitrate: Long): Int =
        if (bitrate > 0) (pieceLength * 1000 / bitrate).toInt().coerceIn(100, 3000) else 250

    private companion object {
        const val MB = 1024L * 1024
        const val MAX_AHEAD_SECONDS = 600L
        const val CRITICAL_SECONDS = 6L
        const val MIN_CRITICAL_BYTES = 8 * MB
        const val MIN_AHEAD_PIECES = 4
        const val MIN_CRITICAL_PIECES = 2
        const val ORDERED_SECONDS = 120L
        const val ORDERED_UNKNOWN_BYTES = 256 * MB
        /** libtorrent keeps time-critical pieces in a sorted list; keep it short on weak CPUs. */
        const val MAX_ORDERED_PIECES = 160
    }
}
