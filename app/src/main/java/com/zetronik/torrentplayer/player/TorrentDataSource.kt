package com.zetronik.torrentplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.zetronik.torrentplayer.torrent.TorrentEngine
import com.zetronik.torrentplayer.torrent.TorrentReader
import com.zetronik.torrentplayer.torrent.TorrentUris
import java.io.InterruptedIOException
import java.io.IOException

/** Media3 data source for `torrent://<infohash>/<fileIndex>` URIs. Reads block until pieces arrive. */
@UnstableApi
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var reader: TorrentReader? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (infoHash, fileIndex) = TorrentUris.parse(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val reader = try {
            engine.openReader(infoHash, fileIndex)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        this.reader = reader
        if (dataSpec.position > reader.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            reader.size - dataSpec.position
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = try {
            checkNotNull(reader).read(position, buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val engine: TorrentEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }
}
