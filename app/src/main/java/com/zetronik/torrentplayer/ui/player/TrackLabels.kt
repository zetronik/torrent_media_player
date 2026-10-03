package com.zetronik.torrentplayer.ui.player

import android.content.res.Resources
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.zetronik.torrentplayer.R
import java.util.Locale

/** "Русский · 5.1 · AC3 · Дубляж" */
fun Resources.trackLabel(format: Format, position: Int, external: Boolean): String {
    val parts = mutableListOf<String>()
    val language = format.language?.takeIf { it != "und" }?.let { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            .takeIf { it.isNotBlank() && it != code }
    }
    language?.let(parts::add)
    format.label?.takeIf { it.isNotBlank() && it != language }?.let(parts::add)
    when (format.channelCount) {
        1 -> parts.add(getString(R.string.track_channels_mono))
        2 -> parts.add(getString(R.string.track_channels_stereo))
        6 -> parts.add("5.1")
        8 -> parts.add("7.1")
        Format.NO_VALUE -> Unit
        else -> parts.add("${format.channelCount}ch")
    }
    // Subtitles parsed during extraction report the original format in `codecs`.
    (codecName(format.sampleMimeType) ?: codecName(format.codecs))?.let(parts::add)
    if (external) parts.add(getString(R.string.track_external))
    return parts.joinToString(" · ").ifEmpty { getString(R.string.track_unknown, position) }
}

private fun codecName(mimeType: String?): String? = when (mimeType) {
    MimeTypes.AUDIO_AC3 -> "AC3"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.TEXT_VTT -> "VTT"
    else -> null
}
