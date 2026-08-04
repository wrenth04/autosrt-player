package com.example.autosrtplayer.data.playback

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.regex.Pattern

private data class ReleaseCacheKey(
    val owner: String,
    val repository: String,
    val tag: String
)

class GitHubReleaseInterceptor(private val patToken: String?) : Interceptor {

    companion object {
        private const val TAG = "GitHubInterceptor"
        private const val MAX_CACHED_RELEASES = 32

        private val GITHUB_RELEASE_PATTERN = Pattern.compile(
            "^https://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+)$"
        )

        // PlayerFactory creates a new interceptor when playback headers change, so keep
        // the release metadata cache shared between interceptor instances.
        private val releaseAssetCache = object : LinkedHashMap<ReleaseCacheKey, Map<String, String>>(
            MAX_CACHED_RELEASES,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ReleaseCacheKey, Map<String, String>>?
            ): Boolean = size > MAX_CACHED_RELEASES
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url.toString()

        // Skip subtitle files - they should be accessed directly without PAT token.
        if (originalUrl.endsWith(".srt", ignoreCase = true) ||
            originalUrl.endsWith(".vtt", ignoreCase = true)) {
            return chain.proceed(originalRequest)
        }

        // Only process if PAT token is available and URL matches GitHub release pattern.
        if (patToken.isNullOrBlank() || !GITHUB_RELEASE_PATTERN.matcher(originalUrl).matches()) {
            return chain.proceed(originalRequest)
        }

        val matcher = GITHUB_RELEASE_PATTERN.matcher(originalUrl)
        if (!matcher.matches()) {
            return chain.proceed(originalRequest)
        }

        val owner = requireNotNull(matcher.group(1))
        val repo = requireNotNull(matcher.group(2))
        val tag = requireNotNull(matcher.group(3))
        val filename = requireNotNull(matcher.group(4))
        val releaseKey = ReleaseCacheKey(owner, repo, tag)

        Log.d(TAG, "Detected GitHub release URL: $originalUrl")
        Log.d(TAG, "  owner=$owner, repo=$repo, tag=$tag, file=$filename")

        try {
            val cachedAssets = getCachedAssets(releaseKey)
            val assets = cachedAssets ?: fetchReleaseAssets(
                chain = chain,
                originalRequest = originalRequest,
                owner = owner,
                repo = repo,
                tag = tag,
                releaseKey = releaseKey
            )

            val assetUrl = assets[filename]
            if (assetUrl == null) {
                Log.e(TAG, "Asset not found: $filename")
                return chain.proceed(originalRequest)
            }

            if (cachedAssets != null) {
                Log.d(TAG, "Using cached asset API URL: $assetUrl")
            } else {
                Log.d(TAG, "Found asset API URL: $assetUrl")
            }

            // Download the asset through the GitHub API so the PAT is applied.
            val assetRequest = originalRequest.newBuilder()
                .url(assetUrl)
                .header("Authorization", "Bearer $patToken")
                .header("Accept", "application/octet-stream")
                .build()

            return chain.proceed(assetRequest)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing GitHub URL", e)
            return chain.proceed(originalRequest)
        }
    }

    private fun fetchReleaseAssets(
        chain: Interceptor.Chain,
        originalRequest: Request,
        owner: String,
        repo: String,
        tag: String,
        releaseKey: ReleaseCacheKey
    ): Map<String, String> {
        // Re-check while holding the lock so concurrent requests can reuse the first
        // completed release lookup instead of overwriting it with another response.
        synchronized(releaseAssetCache) {
            releaseAssetCache[releaseKey]?.let { return it }

            val releaseInfoUrl = "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
            val releaseRequest = originalRequest.newBuilder()
                .url(releaseInfoUrl)
                .header("Authorization", "Bearer $patToken")
                .header("Accept", "application/vnd.github+json")
                .build()

            val releaseResponse = chain.proceed(releaseRequest)
            if (!releaseResponse.isSuccessful) {
                Log.e(TAG, "Failed to fetch release info: ${releaseResponse.code}")
                releaseResponse.close()
                return emptyMap()
            }

            val releaseBody = releaseResponse.body?.string()
            releaseResponse.close()

            if (releaseBody.isNullOrBlank()) {
                Log.e(TAG, "Empty release response body")
                return emptyMap()
            }

            val assets = parseAssetUrls(releaseBody)
            if (assets.isNotEmpty()) {
                releaseAssetCache[releaseKey] = assets
            }
            return assets
        }
    }

    private fun getCachedAssets(releaseKey: ReleaseCacheKey): Map<String, String>? {
        synchronized(releaseAssetCache) {
            return releaseAssetCache[releaseKey]
        }
    }

    private fun parseAssetUrls(jsonBody: String): Map<String, String> {
        return try {
            val assets = JSONObject(jsonBody).optJSONArray("assets") ?: return emptyMap()
            buildMap {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val url = asset.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    put(name, url)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing release JSON", e)
            emptyMap()
        }
    }
}
