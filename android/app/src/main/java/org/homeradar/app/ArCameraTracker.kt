package org.homeradar.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class ArPoseSample(
    val timestampNs: Long,
    val tracking: Boolean,
    val xMeters: Float? = null,
    val zMeters: Float? = null,
    val detail: String
)

data class ArStartResult(
    val ready: Boolean,
    val message: String
)

/**
 * Owns the ARCore session and an OpenGL camera-background renderer.
 * Only timestamped world-space camera positions leave this Android adapter.
 */
class ArCameraTracker(
    context: Context,
    private val onPose: (ArPoseSample) -> Unit
) : GLSurfaceView.Renderer {
    private val activityContext = context
    val view: GLSurfaceView = GLSurfaceView(context)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var cameraTextureId = 0

    @Volatile
    private var textureAssigned = false

    private val resumed = AtomicBoolean(false)
    private var installRequested = false
    private var lastCallbackTimestampNs = 0L
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0

    private val quadVertices = directFloatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val transformedTexCoords = directFloatBuffer(FloatArray(8))

    private var cameraProgram = 0
    private var positionAttribute = -1
    private var texCoordAttribute = -1

    init {
        view.setEGLContextClientVersion(2)
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        view.preserveEGLContextOnPause = true
        view.setRenderer(this)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun resume(activity: Activity): ArStartResult {
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return ArStartResult(false, "Нужно разрешение камеры для визуального трекинга.")
        }

        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return ArStartResult(
                        false,
                        "Установи или обнови Google Play Services for AR, затем вернись в приложение."
                    )
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            var current = session
            if (current == null) {
                current = Session(activity)
                val config = Config(current).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                current.configure(config)
                session = current
                textureAssigned = false
            }

            if (resumed.compareAndSet(false, true)) {
                current.resume()
                view.onResume()
            }
            ArStartResult(true, "Камера ARCore запущена. Держи телефон устойчиво во время прохода.")
        } catch (error: CameraNotAvailableException) {
            resumed.set(false)
            ArStartResult(false, "Камера занята другим приложением. Закрой его и повтори запуск.")
        } catch (error: UnavailableException) {
            resumed.set(false)
            ArStartResult(false, "ARCore недоступен на этом устройстве: \${error.javaClass.simpleName}.")
        } catch (error: SecurityException) {
            resumed.set(false)
            ArStartResult(false, "Android не разрешил доступ к камере.")
        } catch (error: RuntimeException) {
            resumed.set(false)
            ArStartResult(false, "Не удалось запустить ARCore: \${error.javaClass.simpleName}.")
        }
    }

    fun pause() {
        if (!resumed.compareAndSet(true, false)) return
        // Pause GL first so it cannot call Session.update() after Session.pause().
        view.onPause()
        runCatching { session?.pause() }
    }

    fun close() {
        pause()
        runCatching { session?.close() }
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        cameraTextureId = texture[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
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

        cameraProgram = createCameraProgram()
        positionAttribute = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        texCoordAttribute = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
        textureAssigned = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateDisplayGeometry()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val current = session
        if (current == null || !resumed.get() || cameraTextureId == 0) return

        try {
            if (!textureAssigned) {
                current.setCameraTextureName(cameraTextureId)
                textureAssigned = true
            }
            updateDisplayGeometry()

            val frame = current.update()
            drawCameraBackground(frame)

            val timestampNs = frame.timestamp
            if (timestampNs <= 0L || timestampNs - lastCallbackTimestampNs < CALLBACK_INTERVAL_NS) {
                return
            }
            lastCallbackTimestampNs = timestampNs

            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                val translation = camera.pose.translation
                onPose(
                    ArPoseSample(
                        timestampNs = timestampNs,
                        tracking = true,
                        xMeters = translation[0],
                        zMeters = translation[2],
                        detail = "ARCore: tracking"
                    )
                )
            } else {
                onPose(
                    ArPoseSample(
                        timestampNs = timestampNs,
                        tracking = false,
                        detail = "ARCore: \${camera.trackingFailureReason.name.lowercase()}"
                    )
                )
            }
        } catch (error: CameraNotAvailableException) {
            publishRendererError("Камера ARCore стала недоступна.")
        } catch (error: RuntimeException) {
            if (resumed.get()) {
                publishRendererError("Ошибка ARCore: \${error.javaClass.simpleName}.")
            }
        }
    }

    private fun drawCameraBackground(frame: com.google.ar.core.Frame) {
        if (cameraProgram == 0 || frame.timestamp == 0L) return

        quadVertices.position(0)
        transformedTexCoords.clear()
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadVertices,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedTexCoords
        )
        quadVertices.position(0)
        transformedTexCoords.position(0)

        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        val textureLocation = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
        GLES20.glUniform1i(textureLocation, 0)

        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(
            positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quadVertices
        )
        GLES20.glEnableVertexAttribArray(texCoordAttribute)
        GLES20.glVertexAttribPointer(
            texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
    }

    private fun updateDisplayGeometry() {
        val current = session ?: return
        if (lastSurfaceWidth <= 0 || lastSurfaceHeight <= 0) return
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            activityContext.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (activityContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
        runCatching {
            current.setDisplayGeometry(rotation, lastSurfaceWidth, lastSurfaceHeight)
        }
    }

    private fun publishRendererError(message: String) {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (now - lastCallbackTimestampNs < CALLBACK_INTERVAL_NS) return
        lastCallbackTimestampNs = now
        onPose(ArPoseSample(now, false, detail = message))
    }

    private fun createCameraProgram(): Int {
        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()

        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Camera shader link failed: $message")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
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
            throw IllegalStateException("Camera shader compile failed: $message")
        }
        return shader
    }

    companion object {
        private const val CALLBACK_INTERVAL_NS = 100_000_000L

        private fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
