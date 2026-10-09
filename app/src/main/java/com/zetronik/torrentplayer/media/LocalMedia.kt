package com.zetronik.torrentplayer.media

sealed interface FolderItem {
    val name: String
    val path: String

    data class Folder(override val name: String, override val path: String) : FolderItem

    /** [uri] is a `file://` URI; [durationMs] is 0 when MediaStore does not know the file. */
    data class File(
        override val name: String,
        override val path: String,
        val uri: String,
        val kind: LocalKind,
        val size: Long,
        val durationMs: Long,
    ) : FolderItem
}
