package com.zetronik.torrentplayer.ui.settings

import com.zetronik.torrentplayer.torrent.StreamBudget

/**
 * How much video the stream cache holds, for the table under the cache size choice. Pure, unit-tested.
 *
 * The bitrate is file size / duration, so minutes in the cache = cache / file size × duration. The whole
 * cache is counted, including the part kept behind the position for seeks back.
 */
object CacheEstimate {
    private const val GB = 1024L * 1024 * 1024

    val FILE_SIZES = listOf(5 * GB, 20 * GB, 50 * GB)
    val DURATIONS_MINUTES = listOf(60, 120)

    /**
     * [cacheBytes] is the cache a file of [fileSize] would get, `null` when there is no room to stream it;
     * [minutes] follow [DURATIONS_MINUTES].
     */
    data class Row(val fileSize: Long, val cacheBytes: Long?, val minutes: List<Double>)

    /** Rows for [FILE_SIZES] with the cache size [limitBytes] ([StreamBudget.AUTO]) and [freeBytes] free. */
    fun rows(limitBytes: Long, freeBytes: Long): List<Row> = FILE_SIZES.map { size ->
        val cache = StreamBudget.forStream(size, freeBytes, limitBytes)?.totalBytes
        Row(size, cache, DURATIONS_MINUTES.map { if (cache == null) 0.0 else minutes(cache, size, it) })
    }

    fun minutes(cacheBytes: Long, fileSize: Long, durationMinutes: Int): Double =
        minOf(cacheBytes, fileSize).toDouble() / fileSize * durationMinutes

    /** The fixed size [limitBytes] does not fit into [freeBytes]: the cache will be smaller. */
    fun isLimitedBySpace(limitBytes: Long, freeBytes: Long): Boolean =
        limitBytes > StreamBudget.AUTO && limitBytes > StreamBudget.spaceCap(freeBytes)
}
