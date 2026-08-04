package com.example.autosrtplayer.data.playback

import okhttp3.Interceptor
import okhttp3.Response
import java.util.regex.Pattern

class GitHubReleaseInterceptor(private val patToken: String?) : Interceptor {

    companion object {
        private val GITHUB_RELEASE_PATTERN = Pattern.compile(
            "^https://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+)$"
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url.toString()

        // Only process if PAT token is available and URL matches GitHub release pattern
        if (patToken.isNullOrBlank() || !GITHUB_RELEASE_PATTERN.matcher(originalUrl).matches()) {
            return chain.proceed(originalRequest)
        }

        val matcher = GITHUB_RELEASE_PATTERN.matcher(originalUrl)
        if (!matcher.matches()) {
            return chain.proceed(originalRequest)
        }

        val owner = matcher.group(1)
        val repo = matcher.group(2)
        val tag = matcher.group(3)
        val filename = matcher.group(4)

        android.util.Log.d("GitHubInterceptor", "Detected GitHub release URL: $originalUrl")
        android.util.Log.d("GitHubInterceptor", "  owner=$owner, repo=$repo, tag=$tag, file=$filename")

        // First, fetch the release info to get the asset ID
        val releaseInfoUrl = "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
        val releaseRequest = originalRequest.newBuilder()
            .url(releaseInfoUrl)
            .header("Authorization", "Bearer $patToken")
            .header("Accept", "application/vnd.github+json")
            .build()

        try {
            val releaseResponse = chain.proceed(releaseRequest)
            if (!releaseResponse.isSuccessful) {
                android.util.Log.e("GitHubInterceptor", "Failed to fetch release info: ${releaseResponse.code}")
                return releaseResponse
            }

            val releaseBody = releaseResponse.body?.string()
            releaseResponse.close()

            if (releaseBody == null) {
                android.util.Log.e("GitHubInterceptor", "Empty release response body")
                return chain.proceed(originalRequest)
            }

            // Parse JSON to find the asset URL
            val assetUrl = findAssetUrl(releaseBody, filename)
            if (assetUrl == null) {
                android.util.Log.e("GitHubInterceptor", "Asset not found: $filename")
                return chain.proceed(originalRequest)
            }

            android.util.Log.d("GitHubInterceptor", "Found asset API URL: $assetUrl")

            // Now download the asset with the API URL
            val assetRequest = originalRequest.newBuilder()
                .url(assetUrl)
                .header("Authorization", "Bearer $patToken")
                .header("Accept", "application/octet-stream")
                .build()

            return chain.proceed(assetRequest)

        } catch (e: Exception) {
            android.util.Log.e("GitHubInterceptor", "Error processing GitHub URL", e)
            return chain.proceed(originalRequest)
        }
    }

    private fun findAssetUrl(jsonBody: String, filename: String): String? {
        try {
            // Simple JSON parsing to avoid adding dependencies
            val assetsMatch = Regex(""""assets"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                .find(jsonBody) ?: return null

            val assetsJson = assetsMatch.groupValues[1]

            // Find the asset with matching name
            val assetPattern = Regex(
                """"name"\s*:\s*"${Regex.escape(filename)}".*?"url"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            )

            val urlMatch = assetPattern.find(assetsJson)
            return urlMatch?.groupValues?.get(1)

        } catch (e: Exception) {
            android.util.Log.e("GitHubInterceptor", "Error parsing release JSON", e)
            return null
        }
    }
}
