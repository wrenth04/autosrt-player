package com.example.autosrtplayer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp

@Composable
internal fun VrGestureLayer(
    vrViewAngles: VrViewAngles,
    onVrViewDrag: (Float, Float) -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current
    var latestYaw by remember { mutableStateOf(vrViewAngles.yawDegrees) }
    var latestPitch by remember { mutableStateOf(vrViewAngles.pitchDegrees) }
    var screenWidth by remember { mutableStateOf(1f) }
    var screenHeight by remember { mutableStateOf(1f) }

    if (vrViewAngles.yawDegrees != latestYaw || vrViewAngles.pitchDegrees != latestPitch) {
        latestYaw = vrViewAngles.yawDegrees
        latestPitch = vrViewAngles.pitchDegrees
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            screenWidth = size.width.toFloat().coerceAtLeast(1f)
            screenHeight = size.height.toFloat().coerceAtLeast(1f)

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalDrag = Offset.Zero
                var isDragging = false

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: break

                    if (change.pressed) {
                        val drag = change.positionChange()
                        totalDrag += drag

                        if (!isDragging && totalDrag.getDistance() > touchSlop) {
                            isDragging = true
                        }

                        if (isDragging) {
                            change.consume()
                            val yawDelta = -(drag.x / screenWidth) * 180f
                            val pitchDelta = -(drag.y / screenHeight) * 180f
                            latestYaw += yawDelta
                            latestPitch += pitchDelta
                            onVrViewDrag(latestYaw, latestPitch)
                        }
                    } else {
                        if (!isDragging && totalDrag.getDistance() <= touchSlop) {
                            onToggleControls()
                        }
                        break
                    }
                }
            }
        }
    )
}
