package com.example.autosrtplayer.ui.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.autosrtplayer.ui.VrPlaybackConfig
import com.example.autosrtplayer.ui.VrViewAngles
import kotlin.math.abs

/**
 * Composable that manages VR head tracking using device rotation vector sensor.
 * Returns a state holder that provides the current sensor-derived offset angles and a recenter function.
 */
@Composable
fun rememberVrHeadTrackingState(
    enabled: Boolean,
    config: VrPlaybackConfig
): VrHeadTrackingState {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { VrHeadTrackingState() }

    DisposableEffect(enabled, config, lifecycle) {
        if (enabled) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            if (sensorManager != null && rotationSensor != null) {
                var currentRotation = view.display?.rotation ?: Surface.ROTATION_0
                var baseline: OrientationBaseline? = null
                var lastYaw = 0f
                var lastPitch = 0f

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event == null || event.values.size < 4) return

                        val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
                        if (displayRotation != currentRotation) {
                            currentRotation = displayRotation
                            baseline = null
                        }

                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                        val remappedMatrix = FloatArray(9)
                        val (axisX, axisY) = getRemapAxesForRotation(displayRotation)
                        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)

                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(remappedMatrix, orientation)

                        val currentYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        val currentPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()

                        if (baseline == null) {
                            baseline = OrientationBaseline(currentYaw, currentPitch)
                            lastYaw = 0f
                            lastPitch = 0f
                            state.update(VrViewAngles(0f, 0f))
                            return
                        }

                        val relativeYaw = normalizeYawDelta(currentYaw - baseline!!.yaw)
                        val relativePitch = (currentPitch - baseline!!.pitch).coerceIn(-89f, 89f)

                        if (abs(relativeYaw - lastYaw) > 0.1f || abs(relativePitch - lastPitch) > 0.1f) {
                            lastYaw = relativeYaw
                            lastPitch = relativePitch
                            state.update(VrViewAngles(relativeYaw, relativePitch))
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            baseline = null
                            sensorManager.registerListener(
                                listener,
                                rotationSensor,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            sensorManager.unregisterListener(listener)
                        }
                        else -> {}
                    }
                }

                lifecycle.addObserver(lifecycleObserver)

                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    sensorManager.registerListener(
                        listener,
                        rotationSensor,
                        SensorManager.SENSOR_DELAY_GAME
                    )
                }

                state.setRecenterAction {
                    val orientation = FloatArray(3)
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, floatArrayOf(0f, 0f, 0f, 1f))

                    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
                    val remappedMatrix = FloatArray(9)
                    val (axisX, axisY) = getRemapAxesForRotation(displayRotation)
                    SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)

                    SensorManager.getOrientation(remappedMatrix, orientation)
                    val currentYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    val currentPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()

                    baseline = OrientationBaseline(currentYaw, currentPitch)
                    state.update(VrViewAngles(0f, 0f))
                }

                onDispose {
                    lifecycle.removeObserver(lifecycleObserver)
                    sensorManager.unregisterListener(listener)
                    state.setRecenterAction(null)
                }
            } else {
                onDispose { }
            }
        } else {
            state.update(VrViewAngles(0f, 0f))
            state.setRecenterAction(null)
            onDispose { }
        }
    }

    return state
}

/**
 * State holder for VR head tracking sensor data.
 */
class VrHeadTrackingState {
    private var _offset by mutableStateOf(VrViewAngles(0f, 0f))
    private var _recenterAction: (() -> Unit)? = null

    val offset: VrViewAngles
        get() = _offset

    fun update(angles: VrViewAngles) {
        _offset = angles
    }

    fun recenter() {
        _recenterAction?.invoke()
    }

    internal fun setRecenterAction(action: (() -> Unit)?) {
        _recenterAction = action
    }
}

/**
 * Combines manual view angles with sensor offset and clamps the result.
 * When source orientation is flipped vertically, inverts the pitch offset to match the flipped video.
 */
fun combineVrAngles(
    manual: VrViewAngles,
    sensorOffset: VrViewAngles,
    config: VrPlaybackConfig
): VrViewAngles {
    val pitchMultiplier = if (config.shouldFlipSourceVertically()) -1f else 1f
    return VrViewAngles.clampForConfig(
        manual.yawDegrees + sensorOffset.yawDegrees,
        manual.pitchDegrees + (sensorOffset.pitchDegrees * pitchMultiplier),
        config
    )
}

private data class OrientationBaseline(val yaw: Float, val pitch: Float)

/**
 * Returns the remap axes for the given display rotation.
 */
internal fun getRemapAxesForRotation(rotation: Int): Pair<Int, Int> {
    return when (rotation) {
        Surface.ROTATION_0 -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
        Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
        Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
        else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
    }
}

/**
 * Normalizes a yaw delta to the range [-180, 180].
 */
internal fun normalizeYawDelta(delta: Float): Float {
    var normalized = delta
    while (normalized > 180f) normalized -= 360f
    while (normalized < -180f) normalized += 360f
    return normalized
}
