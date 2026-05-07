package com.example.autosrtplayer.data.todayhot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class TodayHotFeed(
    val total: Int,
    val items: List<TodayHotItem>
)

class TodayHotRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun loadTodayHot(): TodayHotFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(TodayHotUrl)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("讀取今日熱門失敗: HTTP ${response.code}")
            }
            val body = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("今日熱門內容為空")
            parse(body)
        }
    }

    internal fun parse(json: String): TodayHotFeed {
        val total = json.extractInt("total") ?: 0
        val itemsSection = json.extractArrayContent("items").orEmpty()
        val items = splitTopLevelObjects(itemsSection).mapNotNull { itemJson ->
            val code = itemJson.extractString("code")?.trim().orEmpty()
            if (code.isBlank()) {
                return@mapNotNull null
            }
            TodayHotItem(
                code = code,
                title = itemJson.extractString("title"),
                coverUrl = itemJson.extractString("cover_url"),
                updatedAt = itemJson.extractString("updated_at"),
                url = itemJson.extractString("url")
            )
        }
        return TodayHotFeed(total = total, items = items)
    }

    private fun String.extractInt(key: String): Int? {
        val match = Regex("\"$key\"\\s*:\\s*(\\d+)").find(this) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun String.extractString(key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").find(this)
            ?: return null
        if (match.groupValues[1] == "null") return null
        return unescapeJsonString(match.groupValues[2])
    }

    private fun String.extractArrayContent(key: String): String? {
        val startMatch = Regex("\"$key\"\\s*:\\s*\\[").find(this) ?: return null
        val startIndex = startMatch.range.last
        var depth = 0
        var inString = false
        var escaped = false
        for (index in startIndex until length) {
            val char = this[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return substring(startIndex + 1, index)
                    }
                }
            }
        }
        return null
    }

    private fun splitTopLevelObjects(content: String): List<String> {
        val items = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var startIndex = -1
        for (index in content.indices) {
            val char = content[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) startIndex = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && startIndex >= 0) {
                        items += content.substring(startIndex, index + 1)
                        startIndex = -1
                    }
                }
            }
        }
        return items
    }

    private fun unescapeJsonString(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\') {
                result.append(char)
                index++
                continue
            }
            if (index + 1 >= value.length) {
                result.append('\\')
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hexStart = index + 2
                    val hexEnd = hexStart + 4
                    if (hexEnd <= value.length) {
                        val hex = value.substring(hexStart, hexEnd)
                        result.append(hex.toIntOrNull(16)?.toChar() ?: "\\u$hex")
                        index += 6
                        continue
                    } else {
                        result.append('\\')
                        index++
                        continue
                    }
                }
                else -> result.append(escaped)
            }
            index += 2
        }
        return result.toString()
    }

    private companion object {
        const val TodayHotUrl = "https://srt.wrenth04.workers.dev/today-hot.json"
    }
}
