package com.zetronik.torrentplayer.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPlanTest {

    private val mb = 1024L * 1024

    @Test
    fun budgetOnNearlyFullTvKeepsReserve() {
        // The TCL test TV: ~558 MB free, 60 GB remux.
        val budget = StreamBudget.forStream(60_000 * mb, 558 * mb)!!
        assertEquals(302 * mb, budget.totalBytes)
        assertTrue("must stay above the low-storage reserve", 558 * mb - budget.totalBytes >= 256 * mb)
        assertTrue(budget.behindBytes in 1..budget.totalBytes / 5)
    }

    @Test
    fun budgetFollowsFileSizeNotFreeSpace() {
        val free = 100_000 * mb
        // Huge remux: capped, a small share of the disk.
        assertEquals(1536 * mb, StreamBudget.forStream(60_000 * mb, free)!!.totalBytes)
        // 10 GB film: about five minutes of video at the estimated bitrate.
        assertTrue(StreamBudget.forStream(10_240 * mb, free)!!.totalBytes in 500 * mb..600 * mb)
        // Small file: the floor.
        val small = StreamBudget.forStream(1400 * mb, free)!!
        assertEquals(128 * mb, small.totalBytes)
        assertTrue(small.behindBytes in 1..small.totalBytes / 5)
    }

    @Test
    fun noBudgetWhenDiskIsFull() {
        val file = 10_000 * mb
        assertNull(StreamBudget.forStream(file, 300 * mb))
        assertNotNull(StreamBudget.forStream(file, StreamBudget.minimumFreeBytes))
        assertNull(StreamBudget.forStream(file, StreamBudget.minimumFreeBytes - 1))
    }

    @Test
    fun fixedSizeFromSettingsIgnoresFileSize() {
        val free = 100_000 * mb
        val small = StreamBudget.forStream(1400 * mb, free, 4096 * mb)!!
        assertEquals(4096 * mb, small.totalBytes)
        assertEquals(256 * mb, small.behindBytes)
        assertFalse(small.timeCapped)
        // Below the automatic floor is allowed too: the user picked it.
        assertEquals(256 * mb, StreamBudget.forStream(60_000 * mb, free, 256 * mb)!!.totalBytes)
        assertTrue(StreamBudget.forStream(60_000 * mb, free)!!.timeCapped)
    }

    @Test
    fun fixedSizeIsStillLimitedByFreeSpace() {
        // 2 GB free: at most 60% of it, and the 256 MB reserve stays free.
        val budget = StreamBudget.forStream(20_000 * mb, 2048 * mb, 4096 * mb)!!
        assertEquals(StreamBudget.spaceCap(2048 * mb), budget.totalBytes)
        assertEquals(2048 * mb * 6 / 10, budget.totalBytes)
        assertNull(StreamBudget.forStream(20_000 * mb, 300 * mb, 4096 * mb))
    }

    @Test
    fun fixedSizeReadAheadIsNotLimitedToTenMinutes() {
        // 1.4 GB movie, 0.25 MB/s media: automatic budgets stop at 10 minutes, a chosen size does not.
        val budget = StreamBudget(aheadBytes = 3840 * mb, behindBytes = 256 * mb, timeCapped = false)
        val window = StreamWindow(0, 1433, mb, budget)
        assertEquals(3840, window.aheadPieces(mb / 4))
    }

    @Test
    fun windowForHighBitrateRemuxIsBoundedByDisk() {
        // 65 GB file, 16 MB pieces, ~7 MB/s media.
        val budget = StreamBudget(aheadBytes = 256 * mb, behindBytes = 46 * mb)
        val window = StreamWindow(0, 4159, 16 * mb, budget)
        val bitrate = 7 * mb
        assertEquals(16, window.aheadPieces(bitrate))
        assertEquals(1000..1015, window.wanted(1000, bitrate))
        assertEquals(1000 - 2, window.discardBefore(1000))
        assertEquals(3, window.criticalPieces(bitrate)) // 42 MB = 6 s of video
    }

    @Test
    fun windowForLowBitrateFileIsBoundedByTime() {
        // 1.4 GB movie, 1 MB pieces, 0.25 MB/s media, plenty of disk.
        val budget = StreamBudget(aheadBytes = 3840 * mb, behindBytes = 256 * mb)
        val window = StreamWindow(0, 1433, mb, budget)
        val bitrate = mb / 4
        assertEquals(150, window.aheadPieces(bitrate)) // 10 minutes, not 3.8 GB
        assertEquals(1433, window.wanted(1400, bitrate).last) // clipped at the end of the file
    }

    @Test
    fun unknownBitrateUsesWholeBudgetAndMinimums() {
        val budget = StreamBudget(aheadBytes = 64 * mb, behindBytes = 8 * mb)
        val window = StreamWindow(10, 100, 32 * mb, budget)
        assertEquals(4, window.aheadPieces(0)) // floor beats the 2-piece budget
        assertEquals(2, window.criticalPieces(0))
        assertEquals(250, window.deadlineStepMs(0))
        assertEquals(1, window.behindPieces())
    }

    @Test
    fun prebufferOnlyWhenDownloadIsTight() {
        val bitrate = 7 * mb // 4K remux
        assertEquals(0, PrebufferPolicy.requiredMs(bitrate, 14 * mb, 2048 * mb)) // 2x faster: player default
        assertEquals(0, PrebufferPolicy.requiredMs(0, 10 * mb, 2048 * mb)) // bitrate unknown yet
        assertEquals(0, PrebufferPolicy.requiredMs(bitrate, 0, 2048 * mb)) // download not ramped up yet
        // 10 MB/s for 7 MB/s media (the TCL TV case): about 14 s
        val tight = PrebufferPolicy.requiredMs(bitrate, 10 * mb, 2048 * mb)
        assertTrue(tight in 10_000..20_000)
        // Slower than the bitrate: more, but never more than the disk can hold or 70 s.
        assertTrue(PrebufferPolicy.requiredMs(bitrate, 5 * mb, 2048 * mb) > tight)
        assertEquals(29_257, PrebufferPolicy.requiredMs(bitrate, 3 * mb, 256 * mb))
        assertTrue(PrebufferPolicy.requiredMs(mb, 1, 4096 * mb) <= 70_000)
    }

    @Test
    fun orderedRangeCoversAboutTwoMinutesButStaysShort() {
        // The Dark Knight on the TCL TV: 4 MB pieces, 7.5 MB/s, 2.2 GB ahead budget.
        val window = StreamWindow(0, 16_000, 4 * mb, StreamBudget(aheadBytes = 2222 * mb, behindBytes = 256 * mb))
        assertEquals(160, window.orderedPieces(7680 * 1024)) // 225 pieces for 120 s, capped
        // Small file, low bitrate: two minutes is only a few pieces, but never fewer than the critical ones.
        val small = StreamWindow(0, 1433, mb, StreamBudget(aheadBytes = 3840 * mb, behindBytes = 256 * mb))
        assertEquals(30, small.orderedPieces(mb / 4))
        assertTrue(small.orderedPieces(mb / 4) >= small.criticalPieces(mb / 4))
    }
}
