package com.zetronik.torrentplayer.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class TrackerScraperTest {

    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun udpConnectRoundTrip() {
        val request = UdpTrackerProtocol.connectRequest(42)
        assertEquals(16, request.size)
        assertEquals(0x41727101980L, ByteBuffer.wrap(request).long)

        val response = ByteBuffer.allocate(16).putInt(0).putInt(42).putLong(777L).array()
        assertEquals(777L, UdpTrackerProtocol.parseConnectResponse(response, 16, 42))
        assertNull(UdpTrackerProtocol.parseConnectResponse(response, 16, 43)) // foreign transaction
        assertNull(UdpTrackerProtocol.parseConnectResponse(response, 8, 42)) // truncated
    }

    @Test
    fun udpScrapeRoundTrip() {
        val request = UdpTrackerProtocol.scrapeRequest(777L, 7, listOf(hexToBytes(hash), hexToBytes(hash)))
        assertEquals(16 + 40, request.size)
        val header = ByteBuffer.wrap(request)
        assertEquals(777L, header.long)
        assertEquals(2, header.int)
        assertEquals(7, header.int)

        val response = ByteBuffer.allocate(UdpTrackerProtocol.responseSize(2))
            .putInt(2).putInt(7)
            .putInt(15).putInt(100).putInt(3)
            .putInt(0).putInt(1).putInt(2)
            .array()
        assertEquals(
            listOf(SwarmInfo(15, 3), SwarmInfo(0, 2)),
            UdpTrackerProtocol.parseScrapeResponse(response, response.size, 7, 2),
        )
        assertNull(UdpTrackerProtocol.parseScrapeResponse(response, response.size - 1, 7, 2))
    }

    @Test
    fun httpScrapeUrlFollowsConvention() {
        assertEquals("http://t.example/scrape", HttpScrape.scrapeUrl("http://t.example/announce"))
        assertEquals("http://t.example/x/scrape.php?pk=1", HttpScrape.scrapeUrl("http://t.example/x/announce.php?pk=1"))
        assertNull(HttpScrape.scrapeUrl("http://t.example/a"))
    }

    @Test
    fun httpScrapeEncodesHashesAndParsesResponse() {
        val raw = hexToBytes(hash)
        val url = HttpScrape.withInfoHashes("http://t.example/scrape?pk=1", listOf(raw))
        assertEquals("http://t.example/scrape?pk=1&info_hash=%01%23Eg%89%AB%CD%EF%01%23Eg%89%AB%CD%EF%01%23Eg", url)

        val key = String(raw, Charsets.ISO_8859_1)
        val body = ("d5:filesd20:" + key + "d8:completei12e10:downloadedi50e10:incompletei4eeee")
            .toByteArray(Charsets.ISO_8859_1)
        assertEquals(mapOf(hash to SwarmInfo(12, 4)), HttpScrape.parseResponse(body))
    }

    @Test
    fun brokenBencodeIsIgnored() {
        assertNull(Bencode.decode("d5:files".toByteArray()))
        assertEquals(emptyMap<String, SwarmInfo>(), HttpScrape.parseResponse("garbage".toByteArray()))
    }

    @Test
    fun magnetTrackersAreDecoded() {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Movie&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce" +
            "&tr=http%3A%2F%2Ft.example%2Fannounce"
        assertEquals(
            listOf("udp://tracker.example:1337/announce", "http://t.example/announce"),
            magnetTrackers(magnet),
        )
        assertEquals(emptyList<String>(), magnetTrackers("magnet:?xt=urn:btih:$hash"))
    }

    @Test
    fun hexRoundTrip() {
        assertArrayEquals(hexToBytes(hash), hexToBytes(bytesToHex(hexToBytes(hash))))
        assertEquals(hash, bytesToHex(hexToBytes(hash)))
    }
}
