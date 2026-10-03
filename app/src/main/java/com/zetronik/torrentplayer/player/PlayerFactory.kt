package com.zetronik.torrentplayer.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.zetronik.torrentplayer.torrent.TorrentEngine
import java.util.Locale

@UnstableApi
object PlayerFactory {

    fun create(context: Context, engine: TorrentEngine, forTorrent: Boolean): ExoPlayer {
        val dataSourceFactory: DataSource.Factory =
            if (forTorrent) TorrentDataSource.Factory(engine) else DefaultDataSource.Factory(context)

        val extractors = DefaultExtractorsFactory()
            // Lets files without an index (some AVI/MPEG-TS rips) still be seekable.
            .setConstantBitrateSeekingEnabled(true)

        // Platform decoders first; FFmpeg covers AC3/E-AC3/DTS/TrueHD on devices without them.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // Byte cap matters on 1–2 GB TV boxes: a 4K remux would otherwise buffer hundreds of megabytes.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 20_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                // Dubs in the device language win; subtitles stay off unless the file marks a track default/forced.
                buildUponParameters().setPreferredAudioLanguage(Locale.getDefault().language)
            )
        }

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}
