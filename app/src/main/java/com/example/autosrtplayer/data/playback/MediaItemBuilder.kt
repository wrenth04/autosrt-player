package com.example.autosrtplayer.data.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.example.autosrtplayer.data.playlist.PlaylistEntry

class MediaItemBuilder {
    fun build(entry: PlaylistEntry): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(entry.mediaUrl)
            .setMediaId(entry.mediaUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .build()
            )

        entry.subtitleUrl?.let { subtitleUrl ->
            val subtitleMimeType = inferSubtitleMimeType(subtitleUrl)
                ?: throw IllegalArgumentException("不支援的字幕格式: $subtitleUrl")
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setMimeType(subtitleMimeType)
                        .setLanguage("zh")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }

        return builder.build()
    }

    fun inferSubtitleMimeType(url: String): String? {
        val normalized = url.substringBefore('?').lowercase()
        return when {
            normalized.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalized.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            else -> null
        }
    }
}
