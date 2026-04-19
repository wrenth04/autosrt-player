package com.example.autosrtplayer.data.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    data class LoadedPlaylist(
        val content: String,
        val resolvedUrl: String
    )

    suspend fun loadFromUrl(url: String): LoadedPlaylist = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("讀取 playlist 失敗: HTTP ${response.code}")
            }
            val content = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Playlist 內容為空")
            LoadedPlaylist(
                content = content,
                resolvedUrl = response.request.url.toString()
            )
        }
    }
}
