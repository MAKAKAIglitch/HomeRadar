package org.homeradar.app

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Minimal ARCore camera background renderer.
 *
 * ARCore owns the camera stream. The same frames provide the camera pose used by
 * the route recorder; no second camera API is opened.
 */
class ArCoreCameraView(
    context: Context,
    private val activity: Activity,
    onPose: (timestampNs: Long, worldX: Double, worldZ: Double) -> Unit,
    onStatus: (String) -> Unit
) : GLSurfaceView(context) {

    private val renderer = CameraRenderer(activity, onPose, onStatus)
    private var session: Session? = null
    private var installRequested = false
    private var cameraResumed = false
    private var destroyed = false

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun resumeCamera() {
        if (destroyed || cameraResumed) return
        try {
            if (session == null) {
                val installStatus = ArCoreApk.getInstance()
                    .requestInstall(activity, !installRequested)
                if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    renderer.reportStatus("Ожидаем установку Google Play Services for AR…")
                    return
                }

                val created = Session(activity)
                val config = Config(created).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                }
                created.configure(config)
                session = created
                renderer.setSession(created)
            }

            session?.resume()
            onResume()
            cameraResumed = true
            renderer.reportStatus("Камера ARCore запущена. Ищем устойчивый трекинг…")
        } catch (error: Exception) {
            cameraResumed = false
            renderer.reportStatus(
                "Не удалось запустить ARCore: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun pauseCamera() {
        if (!cameraResumed) return
        cameraResumed = false
        onPause()
        runCatching { session?.pause() }
    }

    fun closeCamera() {
        if (destroyed) return
        destroyed = true
        pauseCamera()
        runCatching { session?.close() }
        session = null
        renderer.setSession(null)
    }

    private class CameraRenderer(
        private val activity: Activity,
        private val onPose: (Long, Double, Double) -> Unit,
        private val onStatus: (String) -> Unit
    ) : GLSurfaceView.Renderer {

        @Volatile private var session: Session? = null
        private val handler = Handler(Looper.getMainLooper())
        private var textureId = 0
        private var program = 0
        private var positionLocation = -1
        private var textureLocation = -1
        private var samplerLocation = -1
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var lastRotation = -1
        private var boundSession: Session? = null
        private var lastPoseTimestampNs = 0L
        private var lastStatus = ""

        private val vertices: FloatBuffer = directFloatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        )
        private val cameraCoordinates: FloatBuffer = directFloatBuffer(FloatArray(8))

        fun setSession(value: Session?) {
            session = value
            boundSession = null
            lastPoseTimestampNs = 0L
        }

        fun invalidateCameraTexture() {
            boundSession = null
        }

        fun reportStatus(message: String) {
            publishStatus(message)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            textureId = createExternalTexture()
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionLocation = GLES20.glGetAttribLocation(program, "a_Position")
            textureLocation = GLES20.glGetAttribLocation(program, "a_TexCoord")
            samplerLocation = GLES20.glGetUniformLocation(program, "u_CameraTexture")
            boundSession = null
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES20.glViewport(0, 0, width, height)
            updateDisplayGeometry()
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val activeSession = session ?: return

            try {
                if (boundSession !== activeSession) {
                    activeSession.setCameraTextureName(textureId)
                    boundSession = activeSession
                }
                updateDisplayGeometry()
                val frame = activeSession.update()
                drawCamera(frame)

                val camera = frame.camera
                if (camera.trackingState == TrackingState.TRACKING) {
                    val timestamp = frame.timestamp
                    if (timestamp > 0L && timestamp - lastPoseTimestampNs >= POSE_CALLBACK_INTERVAL_NS) {
                        val translation = camera.pose.translation
                        val worldX = translation[0].toDouble()
                        val worldZ = translation[2].toDouble()
                        // ARCore Frame timestamp has no guaranteed clock domain.
                        // Use elapsedRealtime for association with Wi-Fi scan timestamps.
                        val routeTimestampNs = SystemClock.elapsedRealtimeNanos()
                        lastPoseTimestampNs = timestamp
                        handler.post { onPose(routeTimestampNs, worldX, worldZ) }
                    }
                    publishStatus("ARCore: трекинг активен")
                } else {
                    publishStatus("ARCore ищет детали помещения. Медленно поверни телефон и начни движение.")
                }
            } catch (error: Exception) {
                publishStatus("Ошибка ARCore: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        private fun drawCamera(frame: com.google.ar.core.Frame) {
            vertices.position(0)
            cameraCoordinates.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraCoordinates
            )
            vertices.position(0)
            cameraCoordinates.position(0)

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(samplerLocation, 0)

            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(textureLocation)
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, cameraCoordinates)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(textureLocation)
        }

        private fun updateDisplayGeometry() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            val rotation = activity.windowManager.defaultDisplay.rotation
            if (rotation == lastRotation) return
            session?.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight)
            lastRotation = rotation
        }

        private fun publishStatus(message: String) {
            if (message == lastStatus) return
            lastStatus = message
            handler.post { onStatus(message) }
        }

        private fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            return ids[0]
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetProgramInfoLog(result)
                GLES20.glDeleteProgram(result)
                throw IllegalStateException("Не удалось связать OpenGL shader: $message")
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return result
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Не удалось скомпилировать OpenGL shader: $message")
            }
            return shader
        }

        private companion object {
            const val POSE_CALLBACK_INTERVAL_NS = 100_000_000L

            const val VERTEX_SHADER = """
                attribute vec2 a_Position;
                attribute vec2 a_TexCoord;
                varying vec2 v_TexCoord;
                void main() {
                    gl_Position = vec4(a_Position, 0.0, 1.0);
                    v_TexCoord = a_TexCoord;
                }
            """

            const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES u_CameraTexture;
                varying vec2 v_TexCoord;
                void main() {
                    gl_FragColor = texture2D(u_CameraTexture, v_TexCoord);
                }
            """

            fun directFloatBuffer(values: FloatArray): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(values)
                        position(0)
                    }
        }
    }
}
