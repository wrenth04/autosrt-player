package com.example.autosrtplayer.ui.vr

/**
 * Layout calculator for stereo subtitle placement in SBS (side-by-side) VR glasses mode.
 *
 * Returns per-eye viewport bounds and subtitle translation offsets for comfortable stereo depth.
 */
object VrStereoSubtitleLayout {
    /**
     * Fixed horizontal disparity offset in pixels for crossed stereo depth.
     * This translates subtitle content within each eye's viewport.
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
     * Eye-specific layout including viewport and content translation.
     */
    data class EyeLayout(
        val viewport: LayoutBounds,
        val contentTranslationX: Int
    )

    /**
     * Calculates layout for left and right eye viewports with stereo depth translation.
     *
     * @param screenWidth Total screen width in pixels
     * @param screenHeight Total screen height in pixels
     * @return Pair of (leftEyeLayout, rightEyeLayout)
     */
    fun calculateEyeLayouts(screenWidth: Int, screenHeight: Int): Pair<EyeLayout, EyeLayout> {
        val halfWidth = screenWidth / 2
        val offset = STEREO_DISPARITY_OFFSET_PX.coerceIn(0, halfWidth / 4)

        // Left eye viewport: entire left half of screen
        val leftViewport = LayoutBounds(
            left = 0,
            top = 0,
            right = halfWidth,
            bottom = screenHeight
        )

        // Right eye viewport: entire right half of screen
        val rightViewport = LayoutBounds(
            left = halfWidth,
            top = 0,
            right = screenWidth,
            bottom = screenHeight
        )

        // Content translation: shift toward center for crossed disparity
        val leftLayout = EyeLayout(leftViewport, contentTranslationX = offset)
        val rightLayout = EyeLayout(rightViewport, contentTranslationX = -offset)

        return Pair(leftLayout, rightLayout)
    }
}
