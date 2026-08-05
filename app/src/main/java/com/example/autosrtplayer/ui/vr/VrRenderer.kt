package com.example.autosrtplayer.ui.vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.example.autosrtplayer.ui.VrDisplayOutput
import com.example.autosrtplayer.ui.VrFieldOfView
import com.example.autosrtplayer.ui.VrPlaybackConfig
import com.example.autosrtplayer.ui.VrProjection
import com.example.autosrtplayer.ui.VrSourceLayout
import com.example.autosrtplayer.ui.VrTextureCalculator
import com.example.autosrtplayer.ui.VrViewAngles
import com.example.autosrtplayer.ui.vr.depth.DepthFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLSurfaceView

class VrRenderer : GLSurfaceView.Renderer {
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var textureId: Int = -1
    private var program: Int = -1
    private var sphereVertexBuffer: FloatBuffer? = null
    private var sphereTexCoordBuffer: FloatBuffer? = null
    private var sphereVertexCount: Int = 0

    private var flatScreenVertexBuffer: FloatBuffer? = null
    private var flatScreenTexCoordBuffer: FloatBuffer? = null
    private var flatScreenVertexCount: Int = 0
    private var videoAspectRatio: Float = 16f / 9f

    private var config: VrPlaybackConfig = VrPlaybackConfig()
    private var viewAngles: VrViewAngles = VrViewAngles()
    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1
    private var lastFlatScreenSizePercent: Float = VrPlaybackConfig.DEFAULT_FLAT_SCREEN_SIZE_PERCENT
    private var lastVrCameraFov: Float = VrPlaybackConfig.DEFAULT_VR_CAMERA_FOV

    // Depth stereo support
    private var depthTextureId: Int = -1
    private var currentDepthFrame: DepthFrame? = null

    private val mvpMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val textureMatrix = FloatArray(16)

    private var onSurfaceReady: ((Surface) -> Unit)? = null
    private var onRequestRender: (() -> Unit)? = null
    @Volatile
    private var frameUpdateNeeded = false
    @Volatile
    private var isReleased = false

    fun setOnSurfaceReadyListener(listener: ((Surface) -> Unit)?) {
        onSurfaceReady = listener
    }

    fun setOnRequestRenderListener(listener: () -> Unit) {
        onRequestRender = listener
    }

    fun setConfig(newConfig: VrPlaybackConfig) {
        val sizeChanged = newConfig.flatScreenSizePercent != lastFlatScreenSizePercent
        val fovChanged = newConfig.vrCameraFovDegrees != lastVrCameraFov
        config = newConfig
        if (sizeChanged && newConfig.projection == VrProjection.FlatScreen) {
            lastFlatScreenSizePercent = newConfig.flatScreenSizePercent
            generateFlatScreenMesh()
        }
        if (fovChanged) {
            lastVrCameraFov = newConfig.vrCameraFovDegrees
        }
    }

    fun setViewAngles(angles: VrViewAngles) {
        viewAngles = angles
    }

    fun setVideoAspectRatio(aspectRatio: Float) {
        videoAspectRatio = aspectRatio.coerceAtLeast(0.1f)
        generateFlatScreenMesh()
    }

    fun requestFrameUpdate() {
        frameUpdateNeeded = true
        onRequestRender?.invoke()
    }

    /**
     * Update the current depth frame for depth-aware stereo rendering.
     */
    fun setDepthFrame(depthFrame: DepthFrame?) {
        currentDepthFrame = depthFrame
        if (depthFrame != null) {
            if (depthTextureId == -1) {
                depthTextureId = createDepthTexture()
            }
            if (depthTextureId != -1) {
                uploadDepthTexture(depthFrame)
                android.util.Log.d(TAG, "Depth frame uploaded: ${depthFrame.width}x${depthFrame.height}, status=${depthFrame.status}, inference=${depthFrame.inferenceDurationMs}ms")
            }
        }
    }

    private fun createDepthTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        if (id == 0) {
            android.util.Log.e(TAG, "Failed to generate depth texture")
            return -1
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun uploadDepthTexture(depthFrame: DepthFrame) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        val buffer = ByteBuffer.wrap(depthFrame.depthData)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            depthFrame.width,
            depthFrame.height,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (isReleased) {
            android.util.Log.w(TAG, "onSurfaceCreated called on released renderer")
            return
        }

        try {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            textureId = createOESTexture()
            if (textureId == -1) {
                android.util.Log.e(TAG, "Failed to create OES texture")
                return
            }

            program = createShaderProgram()
            if (program == -1) {
                android.util.Log.e(TAG, "Failed to create shader program")
                cleanupGLResources()
                return
            }

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                android.util.Log.e(TAG, "Shader program link failed: $log")
                GLES20.glDeleteProgram(program)
                program = -1
                cleanupGLResources()
                return
            }

            generateSphereMesh()
            generateFlatScreenMesh()
            Matrix.setIdentityM(textureMatrix, 0)

            if (isReleased) {
                android.util.Log.w(TAG, "Renderer released during surface creation, aborting")
                cleanupGLResources()
                return
            }

            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    if (!isReleased) {
                        frameUpdateNeeded = true
                        onRequestRender?.invoke()
                    }
                }
            }
            videoSurface = Surface(surfaceTexture)

            if (videoSurface?.isValid == true) {
                android.util.Log.d(TAG, "GL surface created, texture=$textureId")
                onSurfaceReady?.invoke(videoSurface!!)
            } else {
                android.util.Log.e(TAG, "Created surface is invalid")
                videoSurface?.release()
                videoSurface = null
                surfaceTexture?.release()
                surfaceTexture = null
                cleanupGLResources()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Exception during GL surface creation", e)
            videoSurface?.release()
            videoSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            cleanupGLResources()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (isReleased || program == -1 || textureId == -1) {
            // Skip rendering if core resources are not ready
            return
        }

        try {
            surfaceTexture?.let { st ->
                if (frameUpdateNeeded) {
                    st.updateTexImage()
                    st.getTransformMatrix(textureMatrix)
                    frameUpdateNeeded = false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error updating texture image", e)
            return
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            android.util.Log.w(TAG, "Invalid viewport dimensions: ${viewportWidth}x${viewportHeight}")
            return
        }

        try {
            if (VrTextureCalculator.shouldRenderTwoViewports(config.displayOutput)) {
                renderEye(true, 0, 0, viewportWidth / 2, viewportHeight)
                renderEye(false, viewportWidth / 2, 0, viewportWidth / 2, viewportHeight)
            } else {
                val useLeftEye = when (config.sourceLayout) {
                    VrSourceLayout.Monoscopic -> true
                    VrSourceLayout.SideBySide -> true
                    VrSourceLayout.TopBottom -> true
                }
                renderEye(useLeftEye, 0, 0, viewportWidth, viewportHeight)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error during rendering", e)
        }
    }

    private fun renderEye(isLeftEye: Boolean, x: Int, y: Int, width: Int, height: Int) {
        // Guard against invalid viewport or uninitialized resources
        if (width <= 0 || height <= 0) {
            android.util.Log.w(TAG, "renderEye called with invalid dimensions: ${width}x${height}")
            return
        }
        if (program == -1 || textureId == -1) {
            android.util.Log.w(TAG, "renderEye called without GL program or texture")
            return
        }

        GLES20.glViewport(x, y, width, height)

        val aspect = VrTextureCalculator.calculateEyeProjectionAspect(
            width, height, config.displayOutput, config.stereoAspectMode
        )
        val cameraFov = if (config.displayOutput == VrDisplayOutput.SingleEye) {
            if (config.projection == VrProjection.FlatScreen) {
                VrPlaybackConfig.NORMAL_SCREEN_CAMERA_FOV
            } else {
                config.getEffectiveVrCameraFovDegrees()
            }
        } else {
            if (config.projection == VrProjection.FlatScreen) {
                VrPlaybackConfig.NORMAL_SCREEN_CAMERA_FOV
            } else {
                config.getEffectiveVrCameraFovDegrees()
            }
        }
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.perspectiveM(projectionMatrix, 0, cameraFov, aspect, 0.1f, 100f)

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.rotateM(viewMatrix, 0, -viewAngles.pitchDegrees, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, -viewAngles.yawDegrees, 0f, 1f, 0f)

        Matrix.setIdentityM(modelMatrix, 0)

        val temp = FloatArray(16)
        Matrix.multiplyMM(temp, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, temp, 0)

        val baseCrop = VrTextureCalculator.calculateEyeCrop(config.sourceLayout, isLeftEye)
        val crop = if (config.projection == VrProjection.FlatScreen) {
            val parallaxOffset = VrTextureCalculator.calculateParallaxOffset(config.stereoParallaxPercent, isLeftEye)
            VrTextureCalculator.applyParallaxToCrop(baseCrop, parallaxOffset)
        } else {
            baseCrop
        }

        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        val cropHandle = GLES20.glGetUniformLocation(program, "uTexCrop")
        val projectionTypeHandle = GLES20.glGetUniformLocation(program, "uProjectionType")
        val texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glUniform4f(cropHandle, crop.uMin, crop.uMax, crop.vMin, crop.vMax)

        val projectionType = when (config.projection) {
            VrProjection.Equirectangular -> 0
            VrProjection.Fisheye180 -> 1
            VrProjection.Fisheye360Dual -> 2
            VrProjection.FlatScreen -> 3
        }
        GLES20.glUniform1i(projectionTypeHandle, projectionType)

        val fisheyeFovHandle = GLES20.glGetUniformLocation(program, "uFisheyeFovDegrees")
        GLES20.glUniform1f(fisheyeFovHandle, config.fisheyeFovDegrees)

        val horizontalFovHandle = GLES20.glGetUniformLocation(program, "uHorizontalFovDegrees")
        GLES20.glUniform1f(horizontalFovHandle, config.getEffectiveHorizontalFovDegrees())

        val flipVerticallyHandle = GLES20.glGetUniformLocation(program, "uFlipSourceVertically")
        GLES20.glUniform1i(flipVerticallyHandle, if (config.shouldFlipSourceVertically()) 1 else 0)

        // Depth stereo uniforms
        val depthStereoEnabledHandle = GLES20.glGetUniformLocation(program, "uDepthStereoEnabled")
        val depthStereoStrengthHandle = GLES20.glGetUniformLocation(program, "uDepthStereoStrength")
        val eyeDirectionHandle = GLES20.glGetUniformLocation(program, "uEyeDirection")
        val depthTextureHandle = GLES20.glGetUniformLocation(program, "uDepthTexture")

        val effectiveStrength = config.getEffectiveDepthStereoStrength()
        val depthEnabled = effectiveStrength > 0f && currentDepthFrame != null && depthTextureId != -1

        GLES20.glUniform1i(depthStereoEnabledHandle, if (depthEnabled) 1 else 0)
        GLES20.glUniform1f(depthStereoStrengthHandle, effectiveStrength)
        GLES20.glUniform1f(eyeDirectionHandle, if (isLeftEye) -1f else 1f)

        // Always assign texture unit 1 to the depth sampler to prevent aliasing unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (depthEnabled) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GLES20.glUniform1i(depthTextureHandle, 1)

        // Bind video texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // Use flat screen mesh for FlatScreen projection, sphere mesh otherwise
        val (vertexBuffer, texCoordBuffer, vertexCount) = if (config.projection == VrProjection.FlatScreen) {
            Triple(flatScreenVertexBuffer, flatScreenTexCoordBuffer, flatScreenVertexCount)
        } else {
            Triple(sphereVertexBuffer, sphereTexCoordBuffer, sphereVertexCount)
        }

        // Skip rendering if buffers are not initialized
        if (vertexBuffer == null || texCoordBuffer == null || vertexCount == 0) {
            return
        }

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        if (id == 0) {
            android.util.Log.e(TAG, "Failed to generate OES texture")
            return -1
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createShaderProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        if (vertexShader == 0) {
            android.util.Log.e(TAG, "Failed to load vertex shader")
            return -1
        }

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        if (fragmentShader == 0) {
            android.util.Log.e(TAG, "Failed to load fragment shader")
            GLES20.glDeleteShader(vertexShader)
            return -1
        }

        val prog = GLES20.glCreateProgram()
        if (prog == 0) {
            android.util.Log.e(TAG, "Failed to create shader program")
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return -1
        }

        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)

        // Clean up shaders after linking
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return prog
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            android.util.Log.e(TAG, "Failed to create shader of type $type")
            return 0
        }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e(TAG, "Shader compilation failed (type=$type): $log")
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun generateSphereMesh() {
        val latBands = 60
        val lonBands = 60
        val vertices = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()

        for (lat in 0 until latBands) {
            val theta1 = (lat.toFloat() / latBands) * Math.PI.toFloat()
            val theta2 = ((lat + 1).toFloat() / latBands) * Math.PI.toFloat()

            for (lon in 0 until lonBands) {
                val phi1 = (lon.toFloat() / lonBands) * 2f * Math.PI.toFloat()
                val phi2 = ((lon + 1).toFloat() / lonBands) * 2f * Math.PI.toFloat()

                val v1 = sphereVertex(theta1, phi1)
                val v2 = sphereVertex(theta1, phi2)
                val v3 = sphereVertex(theta2, phi1)
                val v4 = sphereVertex(theta2, phi2)

                val t1 = sphereTexCoord(theta1, phi1)
                val t2 = sphereTexCoord(theta1, phi2)
                val t3 = sphereTexCoord(theta2, phi1)
                val t4 = sphereTexCoord(theta2, phi2)

                vertices.addAll(listOf(v1[0], v1[1], v1[2]))
                vertices.addAll(listOf(v3[0], v3[1], v3[2]))
                vertices.addAll(listOf(v2[0], v2[1], v2[2]))

                texCoords.addAll(listOf(t1[0], t1[1]))
                texCoords.addAll(listOf(t3[0], t3[1]))
                texCoords.addAll(listOf(t2[0], t2[1]))

                vertices.addAll(listOf(v2[0], v2[1], v2[2]))
                vertices.addAll(listOf(v3[0], v3[1], v3[2]))
                vertices.addAll(listOf(v4[0], v4[1], v4[2]))

                texCoords.addAll(listOf(t2[0], t2[1]))
                texCoords.addAll(listOf(t3[0], t3[1]))
                texCoords.addAll(listOf(t4[0], t4[1]))
            }
        }

        sphereVertexCount = vertices.size / 3
        sphereVertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices.toFloatArray())
                position(0)
            }

        sphereTexCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoords.toFloatArray())
                position(0)
            }
    }

    private fun sphereVertex(theta: Float, phi: Float): FloatArray {
        val x = kotlin.math.sin(theta) * kotlin.math.cos(phi)
        val y = kotlin.math.cos(theta)
        val z = kotlin.math.sin(theta) * kotlin.math.sin(phi)
        return floatArrayOf(x, y, z)
    }

    private fun sphereTexCoord(theta: Float, phi: Float): FloatArray {
        val u = phi / (2f * Math.PI.toFloat())
        val v = theta / Math.PI.toFloat()
        return floatArrayOf(u, v)
    }

    private fun generateFlatScreenMesh() {
        // Create a flat screen mesh positioned in front of the camera
        // The screen is placed at z = -3.0, with base height = 2.0 scaled by size setting
        // Width is calculated based on video aspect ratio
        val screenDistance = 3f
        val sizeScale = config.getEffectiveFlatScreenSizePercent() / 100f
        val baseHeight = 2f
        val screenHeight = baseHeight * sizeScale
        val screenWidth = screenHeight * videoAspectRatio

        val halfWidth = screenWidth / 2f
        val halfHeight = screenHeight / 2f
        val z = -screenDistance

        // Two triangles forming a rectangle, facing the camera (+Z direction)
        // Vertices in counter-clockwise order when viewed from camera
        val vertices = floatArrayOf(
            // First triangle
            -halfWidth, -halfHeight, z,  // bottom-left
            -halfWidth,  halfHeight, z,  // top-left
             halfWidth,  halfHeight, z,  // top-right
            // Second triangle
            -halfWidth, -halfHeight, z,  // bottom-left
             halfWidth,  halfHeight, z,  // top-right
             halfWidth, -halfHeight, z   // bottom-right
        )

        flatScreenVertexCount = vertices.size / 3

        // Allocate vertex buffer once, then reuse it by rewriting vertices
        val vertexBuf = flatScreenVertexBuffer
        if (vertexBuf == null || vertexBuf.capacity() < vertices.size) {
            flatScreenVertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(vertices)
                    position(0)
                }
        } else {
            vertexBuf.clear()
            vertexBuf.put(vertices)
            vertexBuf.position(0)
        }

        // Texture coordinates are invariant; allocate once and reuse
        if (flatScreenTexCoordBuffer == null) {
            val texCoords = floatArrayOf(
                // First triangle
                0f, 1f,  // bottom-left
                0f, 0f,  // top-left
                1f, 0f,  // top-right
                // Second triangle
                0f, 1f,  // bottom-left
                1f, 0f,  // top-right
                1f, 1f   // bottom-right
            )
            flatScreenTexCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(texCoords)
                    position(0)
                }
        }
    }

    fun release() {
        if (isReleased) {
            android.util.Log.d(TAG, "release() called on already released renderer")
            return
        }
        isReleased = true
        frameUpdateNeeded = false
        onSurfaceReady = null
        onRequestRender = null

        android.util.Log.d(TAG, "Releasing VR renderer resources")

        // Release Surface and SurfaceTexture on main thread or GL thread
        try {
            videoSurface?.release()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error releasing video surface", e)
        } finally {
            videoSurface = null
        }

        try {
            surfaceTexture?.release()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error releasing surface texture", e)
        } finally {
            surfaceTexture = null
        }

        cleanupGLResources()
    }

    private fun cleanupGLResources() {
        if (textureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = -1
        }
        if (depthTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(depthTextureId), 0)
            depthTextureId = -1
        }
        if (program != -1) {
            GLES20.glDeleteProgram(program)
            program = -1
        }
        sphereVertexBuffer = null
        sphereTexCoordBuffer = null
        flatScreenVertexBuffer = null
        flatScreenTexCoordBuffer = null
        currentDepthFrame = null
    }

    fun getVideoSurface(): Surface? = videoSurface

    companion object {
        private const val TAG = "VrRenderer"

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            varying vec2 vTexCoord;
            varying vec3 vDirection;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
                vDirection = normalize(aPosition.xyz);
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec3 vDirection;
            uniform samplerExternalOES uTexture;
            uniform sampler2D uDepthTexture;
            uniform vec4 uTexCrop;
            uniform int uProjectionType;
            uniform mat4 uTexMatrix;
            uniform float uFisheyeFovDegrees;
            uniform float uHorizontalFovDegrees;
            uniform int uFlipSourceVertically;
            uniform int uDepthStereoEnabled;
            uniform float uDepthStereoStrength;
            uniform float uEyeDirection;

            const float PI = 3.14159265359;

            vec2 applyEquirectangular(vec2 coord) {
                float halfFov = uHorizontalFovDegrees * 0.5;

                // For full 360°, use standard equirectangular mapping
                if (uHorizontalFovDegrees >= 359.0) {
                    float u = mix(uTexCrop.x, uTexCrop.y, coord.x);
                    float v = mix(uTexCrop.z, uTexCrop.w, coord.y);
                    return vec2(u, v);
                }

                // For custom FOV < 360°, map viewing direction to horizontal angle
                vec3 dir = normalize(vDirection);
                float yaw = atan(dir.x, -dir.z);  // Angle relative to forward (-Z)
                float yawDegrees = degrees(yaw);

                // Discard fragments outside FOV range
                if (abs(yawDegrees) > halfFov) {
                    return vec2(-1.0, -1.0);
                }

                // Map yaw from [-halfFov, +halfFov] to [0, 1]
                float u = (yawDegrees + halfFov) / uHorizontalFovDegrees;
                u = mix(uTexCrop.x, uTexCrop.y, u);

                // Keep vertical mapping standard (using vTexCoord.y from sphere)
                float v = mix(uTexCrop.z, uTexCrop.w, coord.y);

                return vec2(u, v);
            }

            vec2 applyFisheye180(vec3 dir) {
                if (dir.z > 0.0) return vec2(-1.0, -1.0);

                float halfFovRadians = radians(uFisheyeFovDegrees * 0.5);
                float theta = acos(-dir.z);

                if (theta > halfFovRadians) return vec2(-1.0, -1.0);

                float r = theta / halfFovRadians;

                vec2 centeredUV = vec2(dir.x, dir.y) * r;
                vec2 discUV = centeredUV * 0.5 + 0.5;

                float u = mix(uTexCrop.x, uTexCrop.y, discUV.x);
                float v = mix(uTexCrop.z, uTexCrop.w, discUV.y);
                return vec2(u, v);
            }

            vec2 applyFisheye360Dual(vec2 coord) {
                vec2 centered = (coord - 0.5) * 2.0;
                float r = length(centered);
                if (r > 1.0) return vec2(-1.0, -1.0);

                bool isLeft = centered.x < 0.0;
                float theta = r * 1.5708;
                float phi = atan(centered.y, abs(centered.x));

                float u = phi / 3.14159;
                float v = theta / 3.14159;

                if (isLeft) {
                    u = mix(uTexCrop.x, uTexCrop.x + (uTexCrop.y - uTexCrop.x) * 0.5, u);
                } else {
                    u = mix(uTexCrop.x + (uTexCrop.y - uTexCrop.x) * 0.5, uTexCrop.y, u);
                }
                v = mix(uTexCrop.z, uTexCrop.w, v);
                return vec2(u, v);
            }

            void main() {
                vec2 coord;
                if (uProjectionType == 0) {
                    coord = applyEquirectangular(vTexCoord);
                } else if (uProjectionType == 1) {
                    coord = applyFisheye180(vDirection);
                } else if (uProjectionType == 2) {
                    coord = applyFisheye360Dual(vTexCoord);
                } else {
                    // uProjectionType == 3: FlatScreen - direct UV mapping with optional depth warp
                    float u = mix(uTexCrop.x, uTexCrop.y, vTexCoord.x);
                    float v = mix(uTexCrop.z, uTexCrop.w, vTexCoord.y);

                    // Apply depth-aware stereo if enabled
                    if (uDepthStereoEnabled == 1 && uDepthStereoStrength > 0.0) {
                        // Sample depth at base UV
                        float depth = texture2D(uDepthTexture, vTexCoord).r;

                        // Convert depth (0-255) to normalized [0, 1]
                        float normalizedDepth = depth;

                        // Calculate horizontal disparity based on depth
                        // Closer objects (higher depth) = more disparity
                        float maxDisparity = uDepthStereoStrength / 100.0;
                        float disparity = normalizedDepth * maxDisparity;

                        // Apply eye-dependent horizontal shift
                        u += uEyeDirection * disparity;

                        // Clamp to valid range
                        u = clamp(u, uTexCrop.x, uTexCrop.y);
                    }

                    coord = vec2(u, v);
                }

                if (coord.x < 0.0) discard;

                if (uFlipSourceVertically == 1) {
                    coord.y = 1.0 - coord.y;
                }

                vec4 texCoord = uTexMatrix * vec4(coord, 0.0, 1.0);
                gl_FragColor = texture2D(uTexture, texCoord.xy);
            }
        """
    }

    private fun Matrix.perspectiveM(
        m: FloatArray, offset: Int,
        fovY: Float, aspect: Float, near: Float, far: Float
    ) {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovY.toDouble()).toFloat() / 2f)
        val rangeInv = 1f / (near - far)

        m[offset + 0] = f / aspect
        m[offset + 1] = 0f
        m[offset + 2] = 0f
        m[offset + 3] = 0f

        m[offset + 4] = 0f
        m[offset + 5] = f
        m[offset + 6] = 0f
        m[offset + 7] = 0f

        m[offset + 8] = 0f
        m[offset + 9] = 0f
        m[offset + 10] = (far + near) * rangeInv
        m[offset + 11] = -1f

        m[offset + 12] = 0f
        m[offset + 13] = 0f
        m[offset + 14] = 2f * far * near * rangeInv
        m[offset + 15] = 0f
    }
}
