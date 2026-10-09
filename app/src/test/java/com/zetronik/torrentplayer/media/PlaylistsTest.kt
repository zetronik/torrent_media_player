package com.zetronik.torrentplayer.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistsTest {

    @Test
    fun extendedM3uKeepsTitles() {
        val text = """
            #EXTM3U
            #EXTINF:3600,Первая серия
            Season 1/01.mkv

            #EXTINF:-1,
            02.mkv
            # comment
            https://example.com/03.mp4
        """.trimIndent()
        assertEquals(
            listOf(
                PlaylistEntry("Season 1/01.mkv", "Первая серия"),
                PlaylistEntry("02.mkv", null),
                PlaylistEntry("https://example.com/03.mp4", null),
            ),
            PlaylistParser.parse(text),
        )
    }

    @Test
    fun plsIsOrderedByEntryNumber() {
        val text = "\uFEFF[playlist]\nFile2=b.mp4\nTitle2=B\nFile1=a.mp4\nNumberOfEntries=2\n"
        assertEquals(
            listOf(PlaylistEntry("a.mp4", null), PlaylistEntry("b.mp4", "B")),
            PlaylistParser.parse(text),
        )
    }

    @Test
    fun cp1251IsDetectedWhenNotUtf8() {
        val text = "Фильм.mkv"
        assertEquals(text, PlaylistParser.decode(text.toByteArray(charset("windows-1251"))))
        assertEquals(text, PlaylistParser.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun locationsAreClassified() {
        assertEquals(PlaylistLocation.Url("https://host/a.mp4"), PlaylistLocation.of("https://host/a.mp4"))
        assertEquals(PlaylistLocation.Absolute("/storage/emulated/0/Movies/a b.mkv"), PlaylistLocation.of("file:///storage/emulated/0/Movies/a%20b.mkv"))
        assertEquals(PlaylistLocation.Absolute("/sdcard/a.mkv"), PlaylistLocation.of("/sdcard/a.mkv"))
        assertEquals(PlaylistLocation.Relative(listOf("..", "Other", "a.mkv")), PlaylistLocation.of("..\\Other\\.\\a.mkv"))
        // Another computer's path: only the file name is worth looking for.
        assertEquals(PlaylistLocation.Relative(listOf("a.mkv")), PlaylistLocation.of("D:\\Video\\a.mkv"))
        assertNull(PlaylistLocation.of("rtmp://host/live"))
    }

    @Test
    fun onlyPlayableFilesAndPlaylistsAreShown() {
        assertEquals(LocalKind.VIDEO, LocalKind.of("Movie.MKV"))
        assertEquals(LocalKind.PLAYLIST, LocalKind.of("list.m3u8"))
        assertNull(LocalKind.of("cover.jpg"))
        assertNull(LocalKind.of("movie.wmv"))
    }
}
