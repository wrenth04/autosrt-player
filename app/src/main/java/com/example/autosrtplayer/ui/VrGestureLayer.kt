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
import androidx.compose.ui.platform.LocalViewConfiguration

@Composable
internal fun VrGestureLayer(
    manualViewAngles: VrViewAngles,
    vrConfig: VrPlaybackConfig,
    onVrViewDrag: (Float, Float) -> Unit,
    onVrFlatScreenSizeChange: (Float) -> Unit,
    onVrCameraFovChange: (Float) -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var latestYaw by remember { mutableStateOf(manualViewAngles.yawDegrees) }
    var latestPitch by remember { mutableStateOf(manualViewAngles.pitchDegrees) }
    val isFlatScreen = vrConfig.projection == VrProjection.FlatScreen

    if (manualViewAngles.yawDegrees != latestYaw || manualViewAngles.pitchDegrees != latestPitch) {
        latestYaw = manualViewAngles.yawDegrees
        latestPitch = manualViewAngles.pitchDegrees
    }

    Box(
        modifier = modifier.pointerInput(touchSlop, isFlatScreen) {
            val screenWidth = size.width.toFloat().coerceAtLeast(1f)
            val screenHeight = size.height.toFloat().coerceAtLeast(1f)

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val trackingPointerId = down.id
                var totalDrag = Offset.Zero
                var isDragging = false
                var hasMultiplePointers = false
                var lastPinchDistance: Float? = null
                var isPinching = false

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)

                    // Handle two-finger pinch for zoom adjustment
                    if (event.changes.size == 2) {
                        val (first, second) = event.changes.take(2)
                        if (first.pressed && second.pressed) {
                            hasMultiplePointers = true
                            val currentDistance = (first.position - second.position).getDistance()

                            if (!isPinching) {
                                isPinching = true
                                lastPinchDistance = currentDistance
                            } else {
                                val previousDistance = lastPinchDistance ?: currentDistance
                                val distanceChange = currentDistance - previousDistance

                                if (isFlatScreen) {
                                    val sizeChange = (distanceChange / 100f) * 10f
                                    val newSize = (vrConfig.flatScreenSizePercent + sizeChange).coerceIn(
                                        VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT,
                                        VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT
                                    )
                                    onVrFlatScreenSizeChange(newSize)
                                } else {
                                    val fovChange = (distanceChange / 100f) * 5f
                                    val newFov = (vrConfig.vrCameraFovDegrees - fovChange).coerceIn(
                                        VrPlaybackConfig.MIN_VR_CAMERA_FOV,
                                        VrPlaybackConfig.MAX_VR_CAMERA_FOV
                                    )
                                    onVrCameraFovChange(newFov)
                                }
                                lastPinchDistance = currentDistance
                                first.consume()
                                second.consume()
                            }
                        }
                    } else {
                        // Finger count changed away from two — reset pinch state so the next
                        // two-finger gesture starts from a clean baseline instead of jumping.
                        if (isPinching) {
                            isPinching = false
                            lastPinchDistance = null
                        }
                    }

                    // Find the change matching our tracked pointer
                    val change = event.changes.find { it.id == trackingPointerId }

                    // If our tracked pointer is gone or cancelled, abort gesture
                    if (change == null || !change.pressed) {
                        // Only toggle if this was a clean single-finger tap (no pinching, no dragging, no multi-touch)
                        if (change != null && !hasMultiplePointers && !isDragging && !isPinching && totalDrag.getDistance() <= touchSlop) {
                            onToggleControls()
                        }
                        break
                    }

                    val drag = change.positionChange()
                    totalDrag += drag

                    if (!isDragging && totalDrag.getDistance() > touchSlop) {
                        isDragging = true
                    }

                    // Only allow single-finger drag for view rotation when not pinching
                    if (isDragging && !hasMultiplePointers && !isPinching) {
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
