package com.zetronik.torrentplayer.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.zetronik.torrentplayer.torrent.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's own file browser over shared storage: internal storage, SD cards and USB drives.
 *
 * It needs no all-files access: with the media permission Android lets an app list every folder and
 * read the media files in it (playlists count as audio media), while other files stay hidden. So the
 * listing already holds only what the app can play; [LocalKind] filters out media it cannot.
 */
class LocalFiles(private val context: Context) {

    data class Volume(val path: String, val name: String, val removable: Boolean, val freeBytes: Long, val totalBytes: Long)

    /** Runtime permissions to request; the first one is required, the rest (playlists, API 33+) optional. */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permissions.first()) == PackageManager.PERMISSION_GRANTED

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the permission was asked for before. Together with `shouldShowRequestPermissionRationale`
     * it tells a permanent denial (only the app settings can grant it then) from a first request.
     */
    var permissionRequested: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_PERMISSION_REQUESTED, value) }

    /** Mounted volumes, internal storage first. */
    suspend fun volumes(): List<Volume> = withContext(Dispatchers.IO) {
        val storage = context.getSystemService(StorageManager::class.java) ?: return@withContext emptyList()
        storage.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { volume ->
                val root = volume.root() ?: return@mapNotNull null
                Volume(root.path, volume.getDescription(context), volume.isRemovable, root.freeSpace, root.totalSpace)
            }
            .distinctBy { it.path }
            .sortedWith(compareBy<Volume> { it.removable }.thenBy(NaturalOrder) { it.name })
    }

    /**
     * Subfolders, then videos and playlists of [path]. Returns null when the folder cannot be read
     * (the drive was pulled out, or the permission revoked).
     */
    suspend fun list(path: String): List<FolderItem>? = withContext(Dispatchers.IO) {
        val dir = File(path)
        val entries = dir.listFiles() ?: return@withContext null
        val atRoot = volumeRoots().any { it == dir.path }
        val folders = entries
            .filter { it.isDirectory && !it.isHidden && !(atRoot && it.name in SYSTEM_FOLDERS) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { FolderItem.Folder(it.name, it.path) }
        val durations by lazy { durations(dir) }
        val files = entries
            .filter { it.isFile }
            .mapNotNull { file ->
                val kind = LocalKind.of(file.name) ?: return@mapNotNull null
                fileItem(file, kind, if (kind == LocalKind.VIDEO) durations[file.path] ?: 0 else 0)
            }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        folders + files
    }

    /**
     * Where [path] lies, for the user: the volume's name and the folder inside it,
     * e.g. "USB-накопитель Kingston › Movies/Series"; empty for a volume root, whose name says it all.
     */
    fun location(path: String, volumes: List<Volume>): String {
        if (volumes.any { it.path == path }) return ""
        val volume = volumes.filter { path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
            ?: return File(path).parent.orEmpty()
        val folder = File(path).parent.orEmpty().removePrefix(volume.path).trim('/')
        return if (folder.isEmpty()) volume.name else "${volume.name} › $folder"
    }

    /** A readable video at [path], for playlists. */
    fun video(file: File): FolderItem.File? =
        file.takeIf { it.isFile && it.canRead() && LocalKind.of(it.name) == LocalKind.VIDEO }
            ?.let { fileItem(it, LocalKind.VIDEO, 0) }

    private fun fileItem(file: File, kind: LocalKind, durationMs: Long) =
        FolderItem.File(file.name, file.path, Uri.fromFile(file).toString(), kind, file.length(), durationMs)

    private fun volumeRoots(): List<String> =
        context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().mapNotNull { it.root()?.path }

    /** Durations of the videos directly in [dir], from MediaStore; files it has not scanned yet are missing. */
    @Suppress("DEPRECATION")
    private fun durations(dir: File): Map<String, Long> = try {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val prefix = dir.path.trimEnd('/') + "/"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION),
            "${MediaStore.Video.Media.DATA} LIKE ? ESCAPE '\\'",
            arrayOf(escapeLike(prefix) + "%"),
            null,
        )?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    if (path.lastIndexOf('/') == prefix.length - 1) put(path, cursor.getLong(1))
                }
            }
        }.orEmpty()
    } catch (e: RuntimeException) {
        Log.w(TAG, "MediaStore query failed for $dir", e)
        emptyMap()
    }

    /** The volume's mount point. API 30 has it directly; before that the app's own external dirs reveal it. */
    private fun StorageVolume.root(): File? {
        if (Build.VERSION.SDK_INT >= 30) return directory
        val storage = context.getSystemService(StorageManager::class.java) ?: return null
        return context.getExternalFilesDirs(null).filterNotNull()
            .firstOrNull { storage.getStorageVolume(it) == this }
            ?.let { File(it.absolutePath.substringBefore("/Android/")) }
    }

    private companion object {
        const val TAG = "LocalFiles"
        const val PREFS_NAME = "local_files"
        const val KEY_PERMISSION_REQUESTED = "permission_requested"

        /** Folders at a volume root that hold no user media. */
        val SYSTEM_FOLDERS = setOf("Android", "LOST.DIR", "System Volume Information")

        fun escapeLike(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
