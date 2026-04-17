package com.example.autosrtplayer.data.playback

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

class PlayerFactory(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    fun create(
        context: Context,
        userAgent: String?,
        referrer: String?
    ): ExoPlayer {
        val requestHeaders = buildMap {
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(requestHeaders)
            .setUserAgent(userAgent ?: "AutoSRT Player")

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
