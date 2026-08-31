package com.example.autosrtplayer.data.restoration

import kotlin.math.max

data class DetectedMosaicRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val areaFraction: Float
)

fun findLargestMosaicRegion(
    probabilities: FloatArray,
    width: Int,
    height: Int,
    threshold: Float,
    minimumAreaFraction: Float = 0.0015f,
    maximumAreaFraction: Float = 0.75f,
    expansionFactor: Float = 1.35f
): DetectedMosaicRegion? {
    require(width > 0 && height > 0) { "Mask dimensions must be positive" }
    require(probabilities.size == width * height) { "Mask length does not match its dimensions" }
    require(threshold in 0f..1f) { "Mask threshold must be between 0 and 1" }
    require(minimumAreaFraction in 0f..1f) { "Minimum area fraction is invalid" }
    require(maximumAreaFraction in minimumAreaFraction..1f) {
        "Maximum area fraction is invalid"
    }
    require(expansionFactor >= 1f) { "Expansion factor must be at least 1" }

    val visited = BooleanArray(probabilities.size)
    val queue = IntArray(probabilities.size)
    var best: Component? = null

    for (start in probabilities.indices) {
        if (visited[start] || probabilities[start] < threshold) continue

        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        var area = 0
        var confidenceSum = 0f
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            area += 1
            confidenceSum += probabilities[index].coerceIn(0f, 1f)
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)

            for (neighborY in max(0, y - 1)..minOf(height - 1, y + 1)) {
                for (neighborX in max(0, x - 1)..minOf(width - 1, x + 1)) {
                    val neighbor = neighborY * width + neighborX
                    if (!visited[neighbor] && probabilities[neighbor] >= threshold) {
                        visited[neighbor] = true
                        queue[tail++] = neighbor
                    }
                }
            }
        }

        val component = Component(
            area = area,
            confidenceSum = confidenceSum,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY
        )
        if (best == null || component.area > best.area) {
            best = component
        }
    }

    val selected = best ?: return null
    val imageArea = width * height
    val areaFraction = selected.area.toFloat() / imageArea
    if (areaFraction !in minimumAreaFraction..maximumAreaFraction) return null

    val boxWidth = selected.maxX - selected.minX + 1
    val boxHeight = selected.maxY - selected.minY + 1
    val boxAreaFraction = boxWidth.toFloat() * boxHeight / imageArea
    if (boxAreaFraction > maximumAreaFraction) return null
    val expandedWidth = (boxWidth * expansionFactor).coerceAtMost(width.toFloat())
    val expandedHeight = (boxHeight * expansionFactor).coerceAtMost(height.toFloat())
    val centerX = (selected.minX + selected.maxX + 1) / 2f
    val centerY = (selected.minY + selected.maxY + 1) / 2f
    val left = (centerX - expandedWidth / 2f).coerceIn(0f, width - expandedWidth)
    val top = (centerY - expandedHeight / 2f).coerceIn(0f, height - expandedHeight)

    return DetectedMosaicRegion(
        left = left / width,
        top = top / height,
        right = (left + expandedWidth) / width,
        bottom = (top + expandedHeight) / height,
        confidence = selected.confidenceSum / selected.area,
        areaFraction = areaFraction
    )
}

private data class Component(
    val area: Int,
    val confidenceSum: Float,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)
