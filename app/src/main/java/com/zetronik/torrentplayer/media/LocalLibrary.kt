package com.zetronik.torrentplayer.media

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the user added to the "Files" tab: videos, playlists and folders, newest first. */
class LocalLibrary(context: Context) {

    @Serializable
    data class Entry(val path: String, val name: String, val isFolder: Boolean, val addedAt: Long) {
        /** Null for folders. */
        val kind: LocalKind? get() = if (isFolder) null else LocalKind.of(name)
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Adding something already there moves it to the top. [name] is the volume's name for a volume root. */
    @Synchronized
    fun add(file: File, name: String = file.name) {
        val entry = Entry(file.path, name, file.isDirectory, System.currentTimeMillis())
        save(listOf(entry) + _entries.value.filterNot { it.path == entry.path })
    }

    @Synchronized
    fun remove(path: String) = save(_entries.value.filterNot { it.path == path })

    private fun load(): List<Entry> = runCatching {
        prefs.getString(KEY_ENTRIES, null)?.let { Json.decodeFromString<List<Entry>>(it) }
    }.getOrNull().orEmpty()

    private fun save(entries: List<Entry>) {
        prefs.edit { putString(KEY_ENTRIES, Json.encodeToString(entries)) }
        _entries.value = entries
    }

    private companion object {
        const val PREFS_NAME = "local_library"
        const val KEY_ENTRIES = "entries"
    }
}
