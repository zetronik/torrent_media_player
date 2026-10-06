package com.zetronik.torrentplayer.ui.player

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.zetronik.torrentplayer.R

/** How the video fits the screen; the scale button cycles through these in order. */
@OptIn(UnstableApi::class)
enum class VideoScale(val resizeMode: Int, @param:StringRes val label: Int) {
    /** Whole frame visible, black bars where the aspect ratios differ. */
    Fit(AspectRatioFrameLayout.RESIZE_MODE_FIT, R.string.player_scale_fit),
    /** Fills the screen width keeping the aspect ratio; a taller frame is cut at the top and bottom. */
    FitWidth(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, R.string.player_scale_fit_width),
    /** Stretched to the whole screen, aspect ratio ignored. */
    Stretch(AspectRatioFrameLayout.RESIZE_MODE_FILL, R.string.player_scale_stretch),
    /** Fills the whole screen keeping the aspect ratio, cutting off whatever sticks out. */
    Crop(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, R.string.player_scale_crop);

    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]
}
