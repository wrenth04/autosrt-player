package com.example.autosrtplayer.ui.restoration

internal class MosaicRegionTracker(
    private val smoothing: Float = 0.4f,
    private val missedDetectionLimit: Int = 3
) {
    init {
        require(smoothing in 0f..1f) { "Smoothing must be between 0 and 1" }
        require(missedDetectionLimit > 0) { "Missed detection limit must be positive" }
    }

    private var currentRegion: NormalizedRegion? = null
    private var missedDetections = 0

    fun update(detectedRegion: NormalizedRegion?): NormalizedRegion? {
        if (detectedRegion == null) {
            if (currentRegion != null) {
                missedDetections += 1
                if (missedDetections >= missedDetectionLimit) {
                    currentRegion = null
                }
            }
            return currentRegion
        }

        currentRegion = smoothTrackedRegion(
            previous = currentRegion,
            current = detectedRegion,
            smoothing = smoothing
        )
        missedDetections = 0
        return currentRegion
    }

    fun reset() {
        currentRegion = null
        missedDetections = 0
    }
}
