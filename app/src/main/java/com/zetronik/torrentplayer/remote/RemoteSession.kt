package com.zetronik.torrentplayer.remote

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The open player as seen by remote commands. All calls happen on the main thread. */
interface RemotePlayback {
    /** Torrent being played, null for a local video. */
    val infoHash: String?
    fun status(): RemoteStatus
    fun execute(command: RemoteCommand)
    /** Switches to another file of the same torrent (or seeks, if it is the current one). */
    fun playFile(fileIndex: Int, positionMs: Long)
}

/** Links the remote receiver to whichever player is open. */
class RemoteSession {
    @Volatile
    var playback: RemotePlayback? = null
        private set

    fun attach(playback: RemotePlayback) {
        this.playback = playback
    }

    fun detach(playback: RemotePlayback) {
        if (this.playback === playback) this.playback = null
    }
}

/** User-visible device name ("Living room TV"), falling back to the model. */
fun deviceName(context: Context): String {
    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    } else {
        null
    }
    return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
