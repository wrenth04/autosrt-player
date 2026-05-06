package com.example.autosrtplayer.data.playlist

private val ScriptBlockRegex = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
private val PackedMarker = "eval(function(p,a,c,k,e,d)"
private val DirectUrlRegex = Regex("""\b(source|playlist)\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE)
private val ResolutionUrlRegex = Regex("""\b(source\d{3,4})\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE)

private val TitlePatterns = listOf(
    Regex("""(?is)<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']+)["']"""),
    Regex("""(?is)<h1[^>]*>(.*?)</h1>"""),
    Regex("""(?is)<title[^>]*>(.*?)</title>""")
)

private val StringLiterals = Regex("""^(['"])(.*)\1$""", RegexOption.DOT_MATCHES_ALL)
private val BaseAlphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

data class MissavExtractionResult(
    val title: String?,
    val mediaUrl: String,
    val allMediaUrls: Map<String, String>
)

class MissavHtmlExtractor {
    fun extract(html: String): MissavExtractionResult {
        val normalizedHtml = html.trim()
        val discoveredUrls = linkedMapOf<String, String>()

        ScriptBlockRegex.findAll(normalizedHtml).forEach { match ->
            val script = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (!script.contains(PackedMarker)) return@forEach

            val unpacked = runCatching { unpackDeanEdwardsScript(script) }.getOrNull() ?: return@forEach
            collectMediaUrls(unpacked, discoveredUrls)
        }

        if (discoveredUrls.isEmpty()) {
            collectMediaUrls(normalizedHtml, discoveredUrls)
        }

        val selected = pickPreferredUrl(discoveredUrls)
            ?: throw IllegalArgumentException("找不到可播放的來源")

        return MissavExtractionResult(
            title = extractTitle(normalizedHtml),
            mediaUrl = normalizeUrl(selected),
            allMediaUrls = discoveredUrls.mapValues { normalizeUrl(it.value) }
        )
    }

    private fun collectMediaUrls(source: String, sink: MutableMap<String, String>) {
        ResolutionUrlRegex.findAll(source).forEach { match ->
            val key = match.groupValues[1].trim().lowercase()
            val value = normalizeUrl(match.groupValues[2].trim())
            sink.putIfAbsent(key, value)
        }

        DirectUrlRegex.findAll(source).forEach { match ->
            val key = match.groupValues[1].trim().lowercase()
            val value = normalizeUrl(match.groupValues[2].trim())
            sink.putIfAbsent(key, value)
        }
    }

    private fun pickPreferredUrl(urls: Map<String, String>): String? {
        val ranked = urls.keys
            .filter { it.startsWith("source") && it.drop(6).all(Char::isDigit) }
            .sortedByDescending { it.drop(6).toIntOrNull() ?: -1 }

        ranked.firstOrNull()?.let { return urls[it] }
        urls["playlist"]?.let { return it }
        urls["source"]?.let { return it }
        return urls.values.firstOrNull()
    }

    private fun normalizeUrl(url: String): String {
        val value = url.trim()
        return when {
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://missav.ai$value"
            else -> "https://missav.ai/$value"
        }
    }

    private fun extractTitle(html: String): String? {
        for (pattern in TitlePatterns) {
            val raw = pattern.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val cleaned = cleanHtmlText(raw)
            if (cleaned.isNotBlank()) return cleaned
        }
        return null
    }

    private fun cleanHtmlText(raw: String): String {
        if (raw.isBlank()) return raw
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(noTags).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHtmlEntities(value: String): String {
        if (value.isEmpty()) return value
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("&#(\\d+);")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
            }
    }

    private fun unpackDeanEdwardsScript(scriptText: String): String? {
        val evalCall = extractEvalCall(scriptText) ?: return null
        val innerCall = evalCall.removePrefix("eval(").removeSuffix(")")
        val bodyStart = innerCall.indexOf('{')
        if (bodyStart < 0) return null
        val bodyEnd = findMatchingDelimiter(innerCall, bodyStart, '{', '}') ?: return null
        val invokeStart = innerCall.indexOf('(', bodyEnd + 1)
        if (invokeStart < 0) return null
        val invokeEnd = innerCall.lastIndexOf(')')
        if (invokeEnd <= invokeStart) return null

        val args = splitTopLevelArguments(innerCall.substring(invokeStart + 1, invokeEnd))
        if (args.size < 4) return null

        val payload = extractLeadingStringLiteral(args[0]) ?: return null
        val radix = args.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        val count = args.getOrNull(2)?.trim()?.toIntOrNull() ?: return null
        val keywords = parseKeywords(args.getOrNull(3).orEmpty())
        if (keywords.isEmpty()) return null

        return unpackPayload(payload, radix, count, keywords)
    }

    private fun extractEvalCall(scriptText: String): String? {
        val start = scriptText.indexOf(PackedMarker)
        if (start < 0) return null

        val indexStart = start + 4
        if (indexStart >= scriptText.length || scriptText[indexStart] != '(') return null

        var depth = 0
        var inSingle = false
        var inDouble = false
        var inTemplate = false
        var escaped = false

        for (index in indexStart until scriptText.length) {
            val ch = scriptText[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (ch == '\\') {
                escaped = true
                continue
            }

            if (!inDouble && !inTemplate && ch == '\'') {
                inSingle = !inSingle
                continue
            }
            if (!inSingle && !inTemplate && ch == '"') {
                inDouble = !inDouble
                continue
            }
            if (!inSingle && !inDouble && ch == '`') {
                inTemplate = !inTemplate
                continue
            }

            if (inSingle || inDouble || inTemplate) continue

            when (ch) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return scriptText.substring(start, index + 1)
                }
            }
        }

        return null
    }

    private fun findMatchingDelimiter(text: String, startIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inSingle = false
        var inDouble = false
        var inTemplate = false
        var escaped = false

        for (index in startIndex until text.length) {
            val ch = text[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (ch == '\\') {
                escaped = true
                continue
            }

            if (!inDouble && !inTemplate && ch == '\'') {
                inSingle = !inSingle
                continue
            }
            if (!inSingle && !inTemplate && ch == '"') {
                inDouble = !inDouble
                continue
            }
            if (!inSingle && !inDouble && ch == '`') {
                inTemplate = !inTemplate
                continue
            }

            if (inSingle || inDouble || inTemplate) continue

            when (ch) {
                open -> depth += 1
                close -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }

        return null
    }

    private fun splitTopLevelArguments(argumentBlock: String): List<String> {
        val args = mutableListOf<String>()
        var current = StringBuilder()
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var inSingle = false
        var inDouble = false
        var inTemplate = false
        var escaped = false

        for (ch in argumentBlock) {
            if (escaped) {
                current.append(ch)
                escaped = false
                continue
            }

            if (ch == '\\') {
                current.append(ch)
                escaped = true
                continue
            }

            if (!inDouble && !inTemplate && ch == '\'') {
                inSingle = !inSingle
                current.append(ch)
                continue
            }
            if (!inSingle && !inTemplate && ch == '"') {
                inDouble = !inDouble
                current.append(ch)
                continue
            }
            if (!inSingle && !inDouble && ch == '`') {
                inTemplate = !inTemplate
                current.append(ch)
                continue
            }

            if (!inSingle && !inDouble && !inTemplate) {
                when (ch) {
                    '(' -> depthParen += 1
                    ')' -> depthParen -= 1
                    '{' -> depthBrace += 1
                    '}' -> depthBrace -= 1
                    '[' -> depthBracket += 1
                    ']' -> depthBracket -= 1
                    ',' -> if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                        args += current.toString().trim()
                        current = StringBuilder()
                        continue
                    }
                }
            }

            current.append(ch)
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) args += tail
        return args
    }

    private fun extractLeadingStringLiteral(expression: String): String? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null
        val quote = trimmed.first()
        if (quote != '\'' && quote != '"') return null

        var index = 1
        var escaped = false
        while (index < trimmed.length) {
            val ch = trimmed[index]
            if (escaped) {
                escaped = false
                index += 1
                continue
            }
            if (ch == '\\') {
                escaped = true
                index += 1
                continue
            }
            if (ch == quote) {
                return decodeJsStringLiteral(trimmed.substring(1, index))
            }
            index += 1
        }
        return null
    }

    private fun parseKeywords(expression: String): List<String> {
        val literal = extractLeadingStringLiteral(expression) ?: return emptyList()
        return literal.split('|').filter { it.isNotBlank() }
    }

    private fun unpackPayload(payload: String, radix: Int, count: Int, keywords: List<String>): String {
        var result = payload
        val tokenCount = count.coerceAtMost(keywords.size)
        for (index in tokenCount - 1 downTo 0) {
            val token = encodeBase(index, radix)
            val replacement = keywords.getOrNull(index).orEmpty()
            if (replacement.isBlank()) continue
            result = result.replace(Regex("\\b${Regex.escape(token)}\\b"), replacement)
        }
        return result
    }

    private fun encodeBase(value: Int, radix: Int): String {
        if (value == 0) return "0"
        val actualRadix = radix.coerceIn(2, BaseAlphabet.length)
        var current = value
        val out = StringBuilder()
        while (current > 0) {
            val remainder = current % actualRadix
            out.append(BaseAlphabet[remainder])
            current /= actualRadix
        }
        return out.reverse().toString()
    }

    private fun decodeJsStringLiteral(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '\\') {
                out.append(ch)
                index += 1
                continue
            }

            index += 1
            if (index >= value.length) {
                out.append('\\')
                break
            }

            when (val next = value[index]) {
                '\\' -> out.append('\\')
                '\'' -> out.append('\'')
                '"' -> out.append('"')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('')
                'x' -> {
                    val hex = value.substringOrNull(index + 1, index + 3)
                    val decoded = hex?.toIntOrNull(16)?.toChar()
                    if (decoded != null) {
                        out.append(decoded)
                        index += 2
                    } else {
                        out.append(next)
                    }
                }
                'u' -> {
                    val hex = value.substringOrNull(index + 1, index + 5)
                    val decoded = hex?.toIntOrNull(16)?.toChar()
                    if (decoded != null) {
                        out.append(decoded)
                        index += 4
                    } else {
                        out.append(next)
                    }
                }
                else -> out.append(next)
            }
            index += 1
        }
        return out.toString()
    }

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? {
        if (startIndex < 0 || endIndex > length || startIndex >= endIndex) return null
        return substring(startIndex, endIndex)
    }
}
