package com.zetronik.torrentplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_torrents")
data class RecentTorrent(
    @PrimaryKey val infoHash: String,
    val name: String,
    val totalSize: Long,
    val magnetUri: String,
    val seeds: Int,
    val peers: Int,
    val lastOpenedAt: Long,
)

/** Key is `<infohash>:<fileIndex>` for torrent files or the content URI for local files. */
@Entity(tableName = "playback_positions")
data class PlaybackPosition(
    @PrimaryKey val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface RecentTorrentDao {
    @Query("SELECT * FROM recent_torrents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<RecentTorrent>>

    @Query("SELECT * FROM recent_torrents WHERE infoHash = :infoHash")
    suspend fun get(infoHash: String): RecentTorrent?

    @Upsert
    suspend fun upsert(torrent: RecentTorrent)

    @Query("UPDATE recent_torrents SET seeds = :seeds, peers = :peers WHERE infoHash = :infoHash")
    suspend fun updateSwarm(infoHash: String, seeds: Int, peers: Int)

    @Query("SELECT infoHash FROM recent_torrents ORDER BY lastOpenedAt DESC LIMIT -1 OFFSET :keep")
    suspend fun hashesBeyond(keep: Int): List<String>

    @Query("SELECT infoHash FROM recent_torrents")
    suspend fun allHashes(): List<String>

    @Query("DELETE FROM recent_torrents WHERE infoHash = :infoHash")
    suspend fun delete(infoHash: String)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE `key` = :key")
    suspend fun get(key: String): PlaybackPosition?

    @Query("SELECT * FROM playback_positions WHERE `key` LIKE :infoHash || ':%'")
    fun observeForTorrent(infoHash: String): Flow<List<PlaybackPosition>>

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM playback_positions WHERE `key` LIKE :infoHash || ':%'")
    suspend fun deleteForTorrent(infoHash: String)
}

@Database(entities = [RecentTorrent::class, PlaybackPosition::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentTorrents(): RecentTorrentDao
    abstract fun playbackPositions(): PlaybackPositionDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "torrent_player.db").build()
    }
}
