package com.example.autosrtplayer.data.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

class PlayerFactory(
    private val okHttpClient: OkHttpClient = Companion.sharedHttpClient
) {
    companion object {
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    @UnstableApi
    fun create(
        context: Context,
        userAgent: String?,
        referrer: String?,
        patToken: String?
    ): ExoPlayer {
        val requestHeaders = buildMap {
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            patToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }

        // Create OkHttpClient with GitHub interceptor if PAT token is provided
        val clientForPlayer = if (!patToken.isNullOrBlank()) {
            okHttpClient.newBuilder()
                .addInterceptor(GitHubReleaseInterceptor(patToken))
                .build()
        } else {
            okHttpClient
        }

        val httpDataSourceFactory = OkHttpDataSource.Factory(clientForPlayer)
            .setDefaultRequestProperties(requestHeaders)
            .setUserAgent(userAgent ?: "AutoSRT Player")

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
