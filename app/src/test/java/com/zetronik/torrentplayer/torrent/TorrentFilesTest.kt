package com.zetronik.torrentplayer.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentFilesTest {

    @Test
    fun episodesAreSortedNaturally() {
        val names = listOf("Show S01E10.mkv", "Show S01E2.mkv", "Show S01E1.mkv", "show S01E03.mkv")
        assertEquals(
            listOf("Show S01E1.mkv", "Show S01E2.mkv", "show S01E03.mkv", "Show S01E10.mkv"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun onlyVideoFilesAreListed() {
        val meta = TorrentMeta(
            infoHash = "hash",
            name = "Show",
            totalSize = 0,
            magnetUri = "",
            files = listOf(
                TorrentFileEntry(0, "Show/Episode 10.mkv", 1),
                TorrentFileEntry(1, "Show/Episode 9.MP4", 1),
                TorrentFileEntry(2, "Show/Episode 9.srt", 1),
                TorrentFileEntry(3, "Show/cover.jpg", 1),
            ),
        )
        assertEquals(listOf(1, 0), meta.videoFiles.map { it.index })
    }

    @Test
    fun subtitlesAreMatchedByVideoName() {
        val video = TorrentFileEntry(0, "Show/Episode 1.mkv", 1)
        val files = listOf(
            video,
            TorrentFileEntry(1, "Show/Episode 2.mkv", 1),
            TorrentFileEntry(2, "Show/Subs/Episode 1.rus.srt", 1),
            TorrentFileEntry(3, "Show/Subs/Episode 1.eng.forced.ass", 1),
            TorrentFileEntry(4, "Show/Subs/Episode 2.rus.srt", 1),
        )
        val subtitles = SubtitleMatcher.find(video, files)
        assertEquals(listOf(3, 2), subtitles.map { it.file.index })
        assertEquals(listOf("en", "ru"), subtitles.map { it.language })
        assertEquals("rus", subtitles[1].label)
    }

    @Test
    fun singleVideoTakesAllSubtitles() {
        val video = TorrentFileEntry(0, "Movie/Movie.2020.1080p.mkv", 1)
        val files = listOf(video, TorrentFileEntry(1, "Movie/Rus Subs/full.srt", 1))
        val subtitles = SubtitleMatcher.find(video, files)
        assertEquals(listOf(1), subtitles.map { it.file.index })
        assertEquals("ru", subtitles.single().language)
    }
}
