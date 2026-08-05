package com.example.autosrtplayer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calculate new FlatScreen size percent from a pinch distance delta.
 * Returns the clamped result within valid range.
 */
internal fun calculateFlatScreenSizeFromPinchDelta(
    currentSizePercent: Float,
    pinchDistanceDelta: Float
): Float {
    val sizeChange = (pinchDistanceDelta / 100f) * 10f
    return (currentSizePercent + sizeChange).coerceIn(
        VrPlaybackConfig.MIN_FLAT_SCREEN_SIZE_PERCENT,
        VrPlaybackConfig.MAX_FLAT_SCREEN_SIZE_PERCENT
    )
}

/**
 * Calculate new camera FOV degrees from a pinch distance delta.
 * Returns the clamped result within valid range.
 * Note: pinch-out (positive delta) reduces FOV for zoom-in effect.
 */
internal fun calculateCameraFovFromPinchDelta(
    currentFovDegrees: Float,
    pinchDistanceDelta: Float
): Float {
    val fovChange = (pinchDistanceDelta / 100f) * 5f
    return (currentFovDegrees - fovChange).coerceIn(
        VrPlaybackConfig.MIN_VR_CAMERA_FOV,
        VrPlaybackConfig.MAX_VR_CAMERA_FOV
    )
}

/**
 * The gesture double-tap window in milliseconds. A single tap is only treated as a
 * controls-toggle after this window elapses without a second tap arriving, so the
 * two taps of a double-tap can be grouped and used to seek instead.
 */
private const val VrDoubleTapTimeoutMs = 250L

/**
 * The seek step (in milliseconds) applied per double-tap in VR mode.
 */
private const val VrSeekStepMs = 60_000L

/**
 * Calculate the seek delta (in milliseconds) for a double-tap in VR mode.
 * A tap on the left half rewinds (negative delta); the right half fast-forwards.
 */
internal fun calculateVrSeekDelta(
    tapPositionX: Float,
    screenWidth: Float,
    seekStepMs: Long
): Long {
    return if (tapPositionX < screenWidth / 2f) -seekStepMs else seekStepMs
}

@Composable
internal fun VrGestureLayer(
    manualViewAngles: VrViewAngles,
    vrConfig: VrPlaybackConfig,
    onVrViewDrag: (Float, Float) -> Unit,
    onVrSeekBy: (Long) -> Unit,
    onVrFlatScreenSizeChange: (Float) -> Unit,
    onVrFlatScreenSizeChangeFinished: (Float) -> Unit,
    onVrCameraFovChange: (Float) -> Unit,
    onVrCameraFovChangeFinished: (Float) -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var latestYaw by remember { mutableStateOf(manualViewAngles.yawDegrees) }
    var latestPitch by remember { mutableStateOf(manualViewAngles.pitchDegrees) }
    val isFlatScreen = vrConfig.projection == VrProjection.FlatScreen

    // Use rememberUpdatedState so the pointer coroutine always sees the current
    // configuration and callbacks without restarting on every config update
    val currentConfig by rememberUpdatedState(vrConfig)
    val currentViewDrag by rememberUpdatedState(onVrViewDrag)
    val currentSeekBy by rememberUpdatedState(onVrSeekBy)
    val currentFlatSizeChange by rememberUpdatedState(onVrFlatScreenSizeChange)
    val currentFlatSizeFinished by rememberUpdatedState(onVrFlatScreenSizeChangeFinished)
    val currentCameraFovChange by rememberUpdatedState(onVrCameraFovChange)
    val currentCameraFovFinished by rememberUpdatedState(onVrCameraFovChangeFinished)
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val scope = rememberCoroutineScope()

    // A pending single-tap that is waiting to see whether a second tap follows (double-tap).
    // Only fired as a controls-toggle if the double-tap window elapses without a second tap.
    var pendingTapJob by remember { mutableStateOf<Job?>(null) }
    var pendingTapTimeMs by remember { mutableStateOf(0L) }

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
                var pinchValue: Float? = null
                var isPinching = false

                fun finishPinch() {
                    val finalValue = pinchValue
                    if (finalValue != null) {
                        // Commit the final gesture value only if a real pinch occurred
                        if (currentConfig.projection == VrProjection.FlatScreen) {
                            currentFlatSizeFinished(finalValue)
                        } else {
                            currentCameraFovFinished(finalValue)
                        }
                    }
                    isPinching = false
                    lastPinchDistance = null
                    pinchValue = null
                }

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)

                    // Handle two-finger pinch for zoom adjustment
                    if (event.changes.size == 2) {
                        val (first, second) = event.changes.take(2)
                        if (first.pressed && second.pressed) {
                            hasMultiplePointers = true
                            val currentDistance = (first.position - second.position).getDistance()

                            if (!isPinching) {
                                // Initialize pinch with current configuration value
                                isPinching = true
                                lastPinchDistance = currentDistance
                                pinchValue = if (currentConfig.projection == VrProjection.FlatScreen) {
                                    currentConfig.flatScreenSizePercent
                                } else {
                                    currentConfig.vrCameraFovDegrees
                                }
                            } else {
                                val previousDistance = lastPinchDistance ?: currentDistance
                                val distanceChange = currentDistance - previousDistance
                                val currentPinchValue = pinchValue ?: run {
                                    // Fallback if pinchValue wasn't initialized
                                    if (currentConfig.projection == VrProjection.FlatScreen) {
                                        currentConfig.flatScreenSizePercent
                                    } else {
                                        currentConfig.vrCameraFovDegrees
                                    }
                                }

                                val newValue = if (currentConfig.projection == VrProjection.FlatScreen) {
                                    calculateFlatScreenSizeFromPinchDelta(currentPinchValue, distanceChange)
                                } else {
                                    calculateCameraFovFromPinchDelta(currentPinchValue, distanceChange)
                                }

                                pinchValue = newValue
                                lastPinchDistance = currentDistance

                                // Send transient update
                                if (currentConfig.projection == VrProjection.FlatScreen) {
                                    currentFlatSizeChange(newValue)
                                } else {
                                    currentCameraFovChange(newValue)
                                }

                                first.consume()
                                second.consume()
                            }
                        }
                    } else {
                        // Finger count changed away from two
                        if (isPinching) {
                            finishPinch()
                        }
                    }

                    // Find the change matching our tracked pointer
                    val change = event.changes.find { it.id == trackingPointerId }

                    // If our tracked pointer is gone or cancelled, end gesture
                    if (change == null || !change.pressed) {
                        // Commit any active pinch first
                        if (isPinching) {
                            finishPinch()
                        }
                        // Handle a clean single-finger tap (no pinching, no dragging, no multi-touch).
                        // A single tap toggles controls, but only after the double-tap window
                        // elapses so a second tap can be grouped as a double-tap to seek instead.
                        if (change != null && !hasMultiplePointers && !isDragging && totalDrag.getDistance() <= touchSlop) {
                            val tapX = change.position.x
                            val existingTap = pendingTapJob
                            val doubleTapDetected = existingTap != null &&
                                SystemClock.elapsedRealtime() - pendingTapTimeMs <= VrDoubleTapTimeoutMs

                            if (doubleTapDetected) {
                                // Two taps within the window: seek, don't toggle controls.
                                existingTap?.cancel()
                                pendingTapJob = null
                                currentSeekBy(calculateVrSeekDelta(tapX, screenWidth, VrSeekStepMs))
                            } else {
                                // First tap: arm the double-tap window, then toggle controls.
                                pendingTapTimeMs = SystemClock.elapsedRealtime()
                                pendingTapJob = scope.launch {
                                    delay(VrDoubleTapTimeoutMs)
                                    pendingTapJob = null
                                    currentToggleControls()
                                }
                            }
                        } else {
                            // Any non-clean release (drag/pinch) cancels a pending double-tap.
                            pendingTapJob?.cancel()
                            pendingTapJob = null
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
                        currentViewDrag(latestYaw, latestPitch)
                    }
                }
            }
        }
    )
}
