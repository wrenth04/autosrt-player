package com.example.autosrtplayer.ui.vr

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

@Composable
fun VrSubtitleOverlay(
    player: ExoPlayer?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
