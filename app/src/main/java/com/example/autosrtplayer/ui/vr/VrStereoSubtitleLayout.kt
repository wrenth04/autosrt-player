package com.example.autosrtplayer.ui.vr

/**
 * Layout calculator for stereo subtitle placement in SBS (side-by-side) VR glasses mode.
 *
 * Returns per-eye bounds with a small fixed crossed-disparity offset so the pair of subtitles
 * fuses at a comfortable near depth when viewed through VR glasses.
 */
object VrStereoSubtitleLayout {
    /**
     * Fixed horizontal disparity offset in pixels for crossed stereo depth.
     * Positive value shifts each subtitle toward screen center (left moves right, right moves left).
     * This creates a comfortable near-plane depth for head-locked text.
     */
    private const val STEREO_DISPARITY_OFFSET_PX = 24

    /**
     * Simple rectangle representation for layout bounds.
     */
    data class LayoutBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    /**
     * Calculates layout bounds for left and right eye subtitle views.
     *
     * @param screenWidth Total screen width in pixels
     * @param screenHeight Total screen height in pixels
     * @return Pair of (leftEyeBounds, rightEyeBounds) with stereo offset applied
     */
    fun calculateEyeBounds(screenWidth: Int, screenHeight: Int): Pair<LayoutBounds, LayoutBounds> {
        val halfWidth = screenWidth / 2
        val offset = STEREO_DISPARITY_OFFSET_PX.coerceIn(0, halfWidth / 4)

        // Left eye: occupies left half, shifted right by offset
        val leftBounds = LayoutBounds(
            left = offset,
            top = 0,
            right = halfWidth,
            bottom = screenHeight
        )

        // Right eye: occupies right half, shifted left by offset
        val rightBounds = LayoutBounds(
            left = halfWidth,
            top = 0,
            right = screenWidth - offset,
            bottom = screenHeight
        )

        return Pair(leftBounds, rightBounds)
    }
}
