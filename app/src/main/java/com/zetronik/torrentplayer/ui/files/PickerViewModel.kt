package com.zetronik.torrentplayer.ui.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.zetronik.torrentplayer.AppContainer
import com.zetronik.torrentplayer.media.FolderItem
import com.zetronik.torrentplayer.media.LocalFiles
import com.zetronik.torrentplayer.ui.navigation.PickerRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The built-in browser in pick mode. It walks from the volumes into folders on one screen, so Back goes
 * up a level; picking adds to [com.zetronik.torrentplayer.media.LocalLibrary] and closes the screen.
 */
class PickerViewModel(savedStateHandle: SavedStateHandle, container: AppContainer) : ViewModel() {

    /** A folder on the walked path; [name] is the volume's name for a volume root. */
    data class Level(val path: String, val name: String)

    sealed interface State {
        data object Loading : State
        data class Volumes(val hasPermission: Boolean, val volumes: List<LocalFiles.Volume>) : State
        data class Folder(val items: List<FolderItem>) : State
        /** The drive was removed while browsing. */
        data object Unavailable : State
    }

    /** True: choose a folder (files are hidden); false: choose a video or playlist. */
    val pickFolders = savedStateHandle.toRoute<PickerRoute>().folders

    private val files = container.localFiles
    private val library = container.localLibrary

    private val _levels = MutableStateFlow<List<Level>>(emptyList())
    val levels: StateFlow<List<Level>> = _levels.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The row to focus after a load: the folder the user came up from. */
    var focusPath: String? = null
        private set

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var loadJob: Job? = null

    /** Also called on resume: the permission may have been granted, a drive plugged in. */
    fun reload() {
        loadJob?.cancel()
        val level = _levels.value.lastOrNull()
        loadJob = viewModelScope.launch {
            _state.value = if (level == null) {
                val granted = files.hasPermission()
                State.Volumes(granted, if (granted) files.volumes() else emptyList())
            } else {
                files.list(level.path)
                    ?.let { items -> State.Folder(if (pickFolders) items.filterIsInstance<FolderItem.Folder>() else items) }
                    ?: State.Unavailable
            }
        }
    }

    fun openVolume(volume: LocalFiles.Volume) = enter(Level(volume.path, volume.name))

    fun openFolder(folder: FolderItem.Folder) = enter(Level(folder.path, folder.name))

    private fun enter(level: Level) {
        focusPath = null
        _levels.value += level
        reload()
    }

    /** Back: one level up. Returns false at the volume list, where Back closes the screen. */
    fun up(): Boolean {
        val levels = _levels.value
        if (levels.isEmpty()) return false
        focusPath = levels.last().path
        _levels.value = levels.dropLast(1)
        reload()
        return true
    }

    fun pick(file: FolderItem.File) = add(File(file.path))

    /** Folder mode: the folder being shown. */
    fun pickCurrent() {
        val level = _levels.value.lastOrNull() ?: return
        add(File(level.path), level.name)
    }

    private fun add(file: File, name: String = file.name) {
        library.add(file, name)
        _done.value = true
    }
}
