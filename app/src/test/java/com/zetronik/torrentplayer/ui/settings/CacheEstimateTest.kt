package com.zetronik.torrentplayer.ui.settings

import com.zetronik.torrentplayer.torrent.StreamBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheEstimateTest {

    private val mb = 1024L * 1024
    private val plenty = 100_000 * mb

    @Test
    fun fixedOneGigabyteMatchesRoadmapTable() {
        val rows = CacheEstimate.rows(1024 * mb, plenty)
        assertEquals(listOf(12.0, 24.0), rows[0].minutes) // 5 GB
        assertEquals(listOf(3.0, 6.0), rows[1].minutes) // 20 GB
        assertEquals(1.2, rows[2].minutes[0], 0.001) // 50 GB
        assertEquals(2.4, rows[2].minutes[1], 0.001)
        assertTrue(rows.all { it.cacheBytes == 1024 * mb })
    }

    @Test
    fun autoFollowsFileSize() {
        val rows = CacheEstimate.rows(StreamBudget.AUTO, plenty)
        assertEquals(284, rows[0].cacheBytes!! / mb) // 5 GB: five minutes at the 90-minute bitrate
        assertEquals(3.3, rows[0].minutes[0], 0.05)
        assertEquals(6.7, rows[0].minutes[1], 0.05)
        assertEquals(1536 * mb, rows[2].cacheBytes) // 50 GB: capped
        assertEquals(1.8, rows[2].minutes[0], 0.05)
    }

    @Test
    fun freeSpaceLimitsTheEstimate() {
        val free = 1024 * mb
        assertTrue(CacheEstimate.isLimitedBySpace(1024 * mb, free))
        assertFalse(CacheEstimate.isLimitedBySpace(StreamBudget.AUTO, free))
        assertFalse(CacheEstimate.isLimitedBySpace(256 * mb, free))
        assertEquals(StreamBudget.spaceCap(free), CacheEstimate.rows(1024 * mb, free)[0].cacheBytes)
        assertNull(CacheEstimate.rows(1024 * mb, 200 * mb)[0].cacheBytes)
    }
}
