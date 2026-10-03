package com.zetronik.torrentplayer.ui.common

import android.content.res.Resources
import com.zetronik.torrentplayer.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun Resources.formatSize(bytes: Long): String {
    val units = intArrayOf(R.string.unit_bytes, R.string.unit_kb, R.string.unit_mb, R.string.unit_gb, R.string.unit_tb)
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (unit == 0 || value >= 100) "%.0f".format(value) else "%.1f".format(value)
    return getString(units[unit], number)
}

fun Resources.formatSpeed(bytesPerSecond: Int): String = getString(R.string.unit_speed, formatSize(bytesPerSecond.toLong()))

/** 1:02:03 or 2:03. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

fun formatDate(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
