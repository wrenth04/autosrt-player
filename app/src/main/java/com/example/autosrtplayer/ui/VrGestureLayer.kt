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
    manualViewAngles: VrViewAngles,
    onVrViewDrag: (Float, Float) -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var latestYaw by remember { mutableStateOf(manualViewAngles.yawDegrees) }
    var latestPitch by remember { mutableStateOf(manualViewAngles.pitchDegrees) }

    if (manualViewAngles.yawDegrees != latestYaw || manualViewAngles.pitchDegrees != latestPitch) {
        latestYaw = manualViewAngles.yawDegrees
        latestPitch = manualViewAngles.pitchDegrees
    }

    Box(
        modifier = modifier.pointerInput(touchSlop) {
            val screenWidth = size.width.toFloat().coerceAtLeast(1f)
            val screenHeight = size.height.toFloat().coerceAtLeast(1f)

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val trackingPointerId = down.id
                var totalDrag = Offset.Zero
                var isDragging = false
                var hasMultiplePointers = false

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)

                    // Detect multiple pointers
                    if (event.changes.size > 1) {
                        hasMultiplePointers = true
                    }

                    // Find the change matching our tracked pointer
                    val change = event.changes.find { it.id == trackingPointerId }

                    // If our tracked pointer is gone or cancelled, abort gesture
                    if (change == null || !change.pressed) {
                        // Only toggle if this was a clean single-finger tap
                        if (change != null && !hasMultiplePointers && !isDragging && totalDrag.getDistance() <= touchSlop) {
                            onToggleControls()
                        }
                        break
                    }

                    val drag = change.positionChange()
                    totalDrag += drag

                    if (!isDragging && totalDrag.getDistance() > touchSlop) {
                        isDragging = true
                    }

                    if (isDragging && !hasMultiplePointers) {
                        change.consume()
                        val yawDelta = -(drag.x / screenWidth) * 180f
                        val pitchDelta = -(drag.y / screenHeight) * 180f
                        latestYaw += yawDelta
                        latestPitch += pitchDelta
                        onVrViewDrag(latestYaw, latestPitch)
                    }
                }
            }
        }
    )
}
