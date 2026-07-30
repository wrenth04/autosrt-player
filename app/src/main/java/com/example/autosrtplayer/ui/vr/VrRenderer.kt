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

    private var config: VrPlaybackConfig = VrPlaybackConfig()
    private var viewAngles: VrViewAngles = VrViewAngles()
    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1

    private val mvpMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    private var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
    private var frameUpdateNeeded = false

    fun setOnSurfaceTextureReadyListener(listener: (SurfaceTexture) -> Unit) {
        onSurfaceTextureReady = listener
    }

    fun setConfig(newConfig: VrPlaybackConfig) {
        config = newConfig
    }

    fun setViewAngles(angles: VrViewAngles) {
        viewAngles = angles
    }

    fun requestFrameUpdate() {
        frameUpdateNeeded = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        textureId = createOESTexture()
        program = createShaderProgram()
        generateSphereMesh()

        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                frameUpdateNeeded = true
            }
        }
        videoSurface = Surface(surfaceTexture)
        onSurfaceTextureReady?.invoke(surfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.let { st ->
            if (frameUpdateNeeded) {
                st.updateTexImage()
                frameUpdateNeeded = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (VrTextureCalculator.shouldRenderTwoViewports(config.displayOutput)) {
            renderEye(true, 0, 0, viewportWidth / 2, viewportHeight)
            renderEye(false, viewportWidth / 2, 0, viewportWidth / 2, viewportHeight)
        } else {
            val isLeftEye = config.sourceLayout == VrSourceLayout.SideBySide
            renderEye(isLeftEye, 0, 0, viewportWidth, viewportHeight)
        }
    }

    private fun renderEye(isLeftEye: Boolean, x: Int, y: Int, width: Int, height: Int) {
        GLES20.glViewport(x, y, width, height)

        val aspect = VrTextureCalculator.calculateViewportAspect(width, height, VrDisplayOutput.SingleEye)
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.perspectiveM(projectionMatrix, 0, 90f, aspect, 0.1f, 100f)

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.rotateM(viewMatrix, 0, -viewAngles.pitchDegrees, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, -viewAngles.yawDegrees, 0f, 1f, 0f)

        Matrix.setIdentityM(modelMatrix, 0)

        val temp = FloatArray(16)
        Matrix.multiplyMM(temp, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, temp, 0)

        val crop = VrTextureCalculator.calculateEyeCrop(config.sourceLayout, isLeftEye)

        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        val cropHandle = GLES20.glGetUniformLocation(program, "uTexCrop")
        val projectionTypeHandle = GLES20.glGetUniformLocation(program, "uProjectionType")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(cropHandle, crop.uMin, crop.uMax, crop.vMin, crop.vMax)

        val projectionType = when (config.projection) {
            VrProjection.Equirectangular -> 0
            VrProjection.Fisheye180 -> 1
            VrProjection.Fisheye360Dual -> 2
        }
        GLES20.glUniform1i(projectionTypeHandle, projectionType)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        sphereVertexBuffer?.let { vb ->
            vb.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vb)
        }

        sphereTexCoordBuffer?.let { tb ->
            tb.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tb)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphereVertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createShaderProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
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

    fun release() {
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (textureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = -1
        }
        if (program != -1) {
            GLES20.glDeleteProgram(program)
            program = -1
        }
    }

    fun getVideoSurface(): Surface? = videoSurface

    companion object {
        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec4 uTexCrop;
            uniform int uProjectionType;

            vec2 applyEquirectangular(vec2 coord) {
                float u = mix(uTexCrop.x, uTexCrop.y, coord.x);
                float v = mix(uTexCrop.z, uTexCrop.w, coord.y);
                return vec2(u, v);
            }

            vec2 applyFisheye180(vec2 coord) {
                vec2 centered = (coord - 0.5) * 2.0;
                float r = length(centered);
                if (r > 1.0) return vec2(-1.0, -1.0);
                float theta = r * 1.5708;
                float phi = atan(centered.y, centered.x);
                float u = (phi / 6.28318 + 0.5);
                float v = theta / 3.14159;
                u = mix(uTexCrop.x, uTexCrop.y, u);
                v = mix(uTexCrop.z, uTexCrop.w, v);
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
                    coord = applyFisheye180(vTexCoord);
                } else {
                    coord = applyFisheye360Dual(vTexCoord);
                }

                if (coord.x < 0.0) discard;
                gl_FragColor = texture2D(uTexture, coord);
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
