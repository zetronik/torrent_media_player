package com.zetronik.torrentplayer.torrent

data class ExternalSubtitle(
    val file: TorrentFileEntry,
    /** ISO 639-1 code when it can be guessed from the file or folder name. */
    val language: String?,
    val label: String,
)

/** Finds external subtitle files in a torrent that belong to a given video file. */
object SubtitleMatcher {

    private val LANGUAGE_TOKENS = mapOf(
        "ru" to "ru", "rus" to "ru", "russian" to "ru",
        "en" to "en", "eng" to "en", "english" to "en",
        "uk" to "uk", "ukr" to "uk", "ua" to "uk", "ukrainian" to "uk",
        "de" to "de", "ger" to "de", "deu" to "de", "german" to "de",
        "fr" to "fr", "fre" to "fr", "fra" to "fr", "french" to "fr",
        "es" to "es", "spa" to "es", "spanish" to "es",
        "ja" to "ja", "jpn" to "ja", "japanese" to "ja",
    )
    private val TOKEN_SPLIT = Regex("[/._\\-\\s\\[\\]()]+")

    fun find(video: TorrentFileEntry, files: List<TorrentFileEntry>): List<ExternalSubtitle> {
        val videoBase = video.name.substringBeforeLast('.').lowercase()
        val singleVideo = files.count { it.kind == FileKind.VIDEO } == 1
        return files
            .filter { it.kind == FileKind.SUBTITLE }
            .mapNotNull { sub ->
                val subBase = sub.name.substringBeforeLast('.')
                val matches = subBase.lowercase().startsWith(videoBase)
                if (!matches && !singleVideo) return@mapNotNull null
                val suffix = if (matches) subBase.substring(videoBase.length).trim('.', ' ', '_', '-') else subBase
                ExternalSubtitle(
                    file = sub,
                    language = guessLanguage(suffix) ?: guessLanguage(sub.folder),
                    label = suffix.ifEmpty { sub.name },
                )
            }
            .sortedWith(compareBy(NaturalOrder) { it.file.path })
    }

    private fun guessLanguage(text: String): String? =
        text.lowercase().split(TOKEN_SPLIT).firstNotNullOfOrNull { LANGUAGE_TOKENS[it] }
}
