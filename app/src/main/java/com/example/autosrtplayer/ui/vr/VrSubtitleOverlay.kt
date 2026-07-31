package com.example.autosrtplayer.ui.vr

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.example.autosrtplayer.ui.VrDisplayOutput
import com.example.autosrtplayer.ui.VrPlaybackConfig
import com.example.autosrtplayer.ui.VrProjection
import com.example.autosrtplayer.ui.VrTextureCalculator

@Composable
fun VrSubtitleOverlay(
    player: ExoPlayer?,
    config: VrPlaybackConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isSbsMode = config.displayOutput == VrDisplayOutput.SbsGlasses
    val isFlatScreen = config.projection == VrProjection.FlatScreen

    if (isSbsMode) {
        // Calculate subtitle offset based on projection type
        val (leftOffsetDp, rightOffsetDp) = if (isFlatScreen) {
            // FlatScreen: move each eye's subtitle with the same parallax direction as
            // its video. Offset permits the required negative left-eye displacement;
            // Compose padding rejects negative values and crashed SBS playback.
            val leftOffsetDp = (
                VrTextureCalculator.calculateParallaxOffset(
                    config.stereoParallaxPercent,
                    isLeftEye = true
                ) * 200f
            ).dp
            val rightOffsetDp = (
                VrTextureCalculator.calculateParallaxOffset(
                    config.stereoParallaxPercent,
                    isLeftEye = false
                ) * 200f
            ).dp
            Pair(leftOffsetDp, rightOffsetDp)
        } else {
            // Panoramic VR: use fixed stereo depth offset
            Pair(24.dp, 24.dp)
        }

        // SBS mode: render two independent subtitle overlays side by side
        androidx.compose.foundation.layout.Row(
            modifier = modifier
        ) {
            // Left eye subtitle
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val leftView = remember {
                    SubtitleView(context).apply {
                        applyVrSubtitleStyle()
                    }
                }

                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                            leftView.setCues(cueGroup.cues)
                        }
                    }
                    player?.addListener(listener)
                    onDispose {
                        player?.removeListener(listener)
                    }
                }

                AndroidView(
                    factory = { leftView },
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = leftOffsetDp)
                )
            }

            // Right eye subtitle
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val rightView = remember {
                    SubtitleView(context).apply {
                        applyVrSubtitleStyle()
                    }
                }

                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                            rightView.setCues(cueGroup.cues)
                        }
                    }
                    player?.addListener(listener)
                    onDispose {
                        player?.removeListener(listener)
                    }
                }

                AndroidView(
                    factory = { rightView },
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = rightOffsetDp)
                )
            }
        }
    } else {
        // Single eye mode: one full-screen subtitle overlay
        val subtitleView = remember {
            SubtitleView(context).apply {
                applyVrSubtitleStyle()
            }
        }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                    subtitleView.setCues(cueGroup.cues)
                }
            }
            player?.addListener(listener)
            onDispose {
                player?.removeListener(listener)
            }
        }

        Box(modifier = modifier) {
            AndroidView(
                factory = { subtitleView },
                modifier = Modifier.fillMaxSize()
            )
        }
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
