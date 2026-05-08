package com.example.autosrtplayer.data.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

class PlayerFactory(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    private fun buildRequestHeaders(userAgent: String?, referrer: String?): Map<String, String> = buildMap {
        userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        referrer?.takeIf { it.isNotBlank() }?.let { referer ->
            put("Referer", referer)
            val origin = runCatching {
                val uri = Uri.parse(referer)
                val scheme = uri.scheme?.takeIf { it.isNotBlank() }
                val host = uri.host?.takeIf { it.isNotBlank() }
                if (scheme != null && host != null) {
                    if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
                } else {
                    null
                }
            }.getOrNull()
            origin?.let { put("Origin", it) }
        }
    }

    @UnstableApi
    fun create(
        context: Context,
        userAgent: String?,
        referrer: String?
    ): ExoPlayer {
        val requestHeaders = buildRequestHeaders(userAgent, referrer)

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(requestHeaders)
            .setUserAgent(userAgent ?: "AutoSRT Player")

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
