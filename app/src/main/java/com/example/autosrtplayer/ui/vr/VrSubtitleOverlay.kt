package com.example.autosrtplayer.ui.vr

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.example.autosrtplayer.ui.VrDisplayOutput
import com.example.autosrtplayer.ui.VrPlaybackConfig

@Composable
fun VrSubtitleOverlay(
    player: ExoPlayer?,
    config: VrPlaybackConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isSbsMode = config.displayOutput == VrDisplayOutput.SbsGlasses

    val containerView = remember(isSbsMode) {
        if (isSbsMode) {
            createStereoSubtitleContainer(context)
        } else {
            createSingleSubtitleView(context)
        }
    }

    DisposableEffect(player, isSbsMode) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (isSbsMode && containerView is StereoSubtitleContainer) {
                    containerView.setCues(cueGroup.cues)
                } else if (containerView is SubtitleView) {
                    containerView.setCues(cueGroup.cues)
                }
            }
        }

        player?.addListener(listener)

        onDispose {
            player?.removeListener(listener)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { containerView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun createSingleSubtitleView(context: Context): SubtitleView {
    return SubtitleView(context).apply {
        applyVrSubtitleStyle()
    }
}

private fun createStereoSubtitleContainer(context: Context): StereoSubtitleContainer {
    return StereoSubtitleContainer(context)
}

/**
 * Custom FrameLayout that automatically positions left and right subtitle views
 * based on the actual measured size from the Android view lifecycle.
 */
private class StereoSubtitleContainer(context: Context) : FrameLayout(context) {
    private val leftEyeContainer: FrameLayout
    private val rightEyeContainer: FrameLayout
    private val leftSubtitleView: SubtitleView
    private val rightSubtitleView: SubtitleView

    init {
        // Create clipping containers for each eye
        leftEyeContainer = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }

        rightEyeContainer = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }

        // Create subtitle views that fill their respective eye containers
        leftSubtitleView = SubtitleView(context).apply {
            applyVrSubtitleStyle()
        }

        rightSubtitleView = SubtitleView(context).apply {
            applyVrSubtitleStyle()
        }

        // Add subtitle views to their containers
        leftEyeContainer.addView(leftSubtitleView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        rightEyeContainer.addView(rightSubtitleView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        // Add eye containers to this parent container
        addView(leftEyeContainer)
        addView(rightEyeContainer)
    }

    fun setCues(cues: List<androidx.media3.common.text.Cue>) {
        leftSubtitleView.setCues(cues)
        rightSubtitleView.setCues(cues)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateEyeLayouts(w, h)
    }

    private fun updateEyeLayouts(containerWidth: Int, containerHeight: Int) {
        if (containerWidth <= 0 || containerHeight <= 0) return

        val (leftLayout, rightLayout) = VrStereoSubtitleLayout.calculateEyeLayouts(
            containerWidth,
            containerHeight
        )

        // Position left eye container to cover left half of screen
        leftEyeContainer.layoutParams = LayoutParams(
            leftLayout.viewport.width(),
            leftLayout.viewport.height()
        ).apply {
            leftMargin = leftLayout.viewport.left
            topMargin = leftLayout.viewport.top
        }

        // Position right eye container to cover right half of screen
        rightEyeContainer.layoutParams = LayoutParams(
            rightLayout.viewport.width(),
            rightLayout.viewport.height()
        ).apply {
            leftMargin = rightLayout.viewport.left
            topMargin = rightLayout.viewport.top
        }

        // Apply stereo depth translation to subtitle content
        leftSubtitleView.translationX = leftLayout.contentTranslationX.toFloat()
        rightSubtitleView.translationX = rightLayout.contentTranslationX.toFloat()
    }
}

private fun SubtitleView.applyVrSubtitleStyle() {
    setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            Color.argb(0x66, 0, 0, 0),
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            Color.BLACK,
            null
        )
    )
    setBottomPaddingFraction(0.08f)
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(true)
}
