package com.zetronik.torrentplayer.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentInputTest {

    @Test
    fun magnetIsExtractedFromSurroundingText() {
        val text = "Смотри: magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie крутой фильм"
        assertEquals(
            TorrentInput.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"),
            TorrentInput.parse(text),
        )
    }

    @Test
    fun bareHexHashBecomesMagnet() {
        assertEquals(
            TorrentInput.Magnet("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567"),
            TorrentInput.parse("  0123456789ABCDEF0123456789ABCDEF01234567\n"),
        )
    }

    @Test
    fun base32HashBecomesMagnet() {
        assertEquals(
            TorrentInput.Magnet("magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"),
            TorrentInput.parse("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"),
        )
    }

    @Test
    fun httpLinkIsUrl() {
        assertEquals(
            TorrentInput.Url("https://example.org/download.php?id=42"),
            TorrentInput.parse("https://example.org/download.php?id=42"),
        )
    }

    @Test
    fun sourceRoundTrips() {
        val inputs = listOf(
            TorrentInput.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            TorrentInput.Url("http://example.org/a.torrent"),
            TorrentInput.TorrentFile("/data/user/0/app/cache/incoming/1.torrent"),
        )
        for (input in inputs) assertEquals(input, TorrentInput.parse(input.source))
    }

    @Test
    fun unrelatedTextIsRejected() {
        assertNull(TorrentInput.parse("просто текст"))
        assertNull(TorrentInput.parse(""))
        assertNull(TorrentInput.parse(null))
    }
}
