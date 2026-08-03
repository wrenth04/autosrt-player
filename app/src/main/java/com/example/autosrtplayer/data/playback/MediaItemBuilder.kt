package com.example.autosrtplayer.data.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.example.autosrtplayer.data.playlist.PlaylistEntry

class MediaItemBuilder {
    fun build(entry: PlaylistEntry, subtitleSource: String? = entry.subtitleUrl): MediaItem {
        val mimeType = inferMediaMimeType(entry.mediaUrl)
        android.util.Log.d("MediaItemBuilder", "Building media item: url=${entry.mediaUrl}, mimeType=$mimeType")
        val builder = MediaItem.Builder()
            .setUri(entry.mediaUrl)
            .setMediaId(entry.mediaUrl)
            .setMimeType(mimeType)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .build()
            )

        subtitleSource?.let { source ->
            val subtitleMimeType = inferSubtitleMimeType(source)
                ?: throw IllegalArgumentException("不支援的字幕格式: $source")
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(source))
                        .setMimeType(subtitleMimeType)
                        .setLanguage("zh")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }

        return builder.build()
    }

    private fun inferMediaMimeType(url: String): String {
        val normalized = url.substringBefore('?').lowercase()
        return when {
            normalized.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            else -> MimeTypes.APPLICATION_M3U8 // default to HLS
        }
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
