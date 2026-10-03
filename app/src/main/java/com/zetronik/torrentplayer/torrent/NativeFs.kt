package com.zetronik.torrentplayer.torrent

internal object NativeFs {
    init {
        System.loadLibrary("torrentplayer_fs")
    }

    /**
     * Frees the disk blocks of `[offset, offset + length)` in [path]; the file keeps its size and reads
     * of the range return zeros. Returns 0 or a negated errno (e.g. -EOPNOTSUPP on filesystems without
     * hole punching).
     */
    @JvmStatic
    external fun punchHole(path: String, offset: Long, length: Long): Int
}
