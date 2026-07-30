package com.example.autosrtplayer.ui.vr

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
                if (isSbsMode && containerView is FrameLayout) {
                    // Update both subtitle views
                    val leftView = containerView.getChildAt(0) as? SubtitleView
                    val rightView = containerView.getChildAt(1) as? SubtitleView
                    leftView?.setCues(cueGroup.cues)
                    rightView?.setCues(cueGroup.cues)
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
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (isSbsMode && view is FrameLayout) {
                    updateStereoBounds(view)
                }
            }
        )
    }
}

private fun createSingleSubtitleView(context: android.content.Context): SubtitleView {
    return SubtitleView(context).apply {
        applyVrSubtitleStyle()
    }
}

private fun createStereoSubtitleContainer(context: android.content.Context): FrameLayout {
    val container = FrameLayout(context)

    val leftSubtitleView = SubtitleView(context).apply {
        applyVrSubtitleStyle()
    }

    val rightSubtitleView = SubtitleView(context).apply {
        applyVrSubtitleStyle()
    }

    container.addView(leftSubtitleView)
    container.addView(rightSubtitleView)

    return container
}

private fun updateStereoBounds(container: FrameLayout) {
    val width = container.width
    val height = container.height

    if (width <= 0 || height <= 0) return

    val (leftBounds, rightBounds) = VrStereoSubtitleLayout.calculateEyeBounds(width, height)

    val leftView = container.getChildAt(0)
    val rightView = container.getChildAt(1)

    leftView?.let {
        val params = it.layoutParams as FrameLayout.LayoutParams
        params.width = leftBounds.width()
        params.height = leftBounds.height()
        params.leftMargin = leftBounds.left
        params.topMargin = leftBounds.top
        it.layoutParams = params
    }

    rightView?.let {
        val params = it.layoutParams as FrameLayout.LayoutParams
        params.width = rightBounds.width()
        params.height = rightBounds.height()
        params.leftMargin = rightBounds.left
        params.topMargin = rightBounds.top
        it.layoutParams = params
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
