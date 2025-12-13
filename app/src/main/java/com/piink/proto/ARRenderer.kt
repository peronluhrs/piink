package com.piink.proto

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ARRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- COMMUNICATION ---
    @Volatile var uiMessage: String = "Initialisation..."
    @Volatile var shouldCapture: Boolean = false

    // ANCRES
    var anchorA: Anchor? = null
    var anchorB: Anchor? = null

    // OPENGL VARS
    private var cameraTextureId = -1
    private var bgProgramId = -1
    private var bgPositionAttrib = -1
    private var bgTexCoordAttrib = -1
    private var bgTextureUniform = -1

    private var pointProgramId = -1
    private var pointPositionAttrib = -1
    private var pointMvpUniform = -1
    private var pointColorUniform = -1
    private var pointSizeUniform = -1

    private val bgVertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val bgTexCoords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val bgTransformedTexCoords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val measurePointVertices: FloatBuffer

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    var currentSession: Session? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var isReady = false

    init {
        bgVertices.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        bgTexCoords.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)).position(0)

        // Cube de 3cm pour les points
        val s = 0.015f
        val pCoords = floatArrayOf(-s, -s, s, s, -s, s, s, s, s, -s, s, s, -s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s)
        measurePointVertices = ByteBuffer.allocateDirect(pCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(pCoords)
        measurePointVertices.position(0)
    }

    fun triggerCapture() {
        shouldCapture = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        val bgV = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){gl_Position=p;v=t;}"
        val bgF = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 v; void main(){gl_FragColor=texture2D(s,v);}"
        bgProgramId = loadProgram(bgV, bgF)
        bgPositionAttrib = GLES20.glGetAttribLocation(bgProgramId, "p")
        bgTexCoordAttrib = GLES20.glGetAttribLocation(bgProgramId, "t")
        bgTextureUniform = GLES20.glGetUniformLocation(bgProgramId, "s")

        val pV = "uniform mat4 mvp; uniform float size; attribute vec4 p; void main(){gl_Position=mvp*p; gl_PointSize=size;}"
        val pF = "precision mediump float; uniform vec4 c; void main(){gl_FragColor=c;}"
        pointProgramId = loadProgram(pV, pF)
        pointPositionAttrib = GLES20.glGetAttribLocation(pointProgramId, "p")
        pointMvpUniform = GLES20.glGetUniformLocation(pointProgramId, "mvp")
        pointColorUniform = GLES20.glGetUniformLocation(pointProgramId, "c")
        pointSizeUniform = GLES20.glGetUniformLocation(pointProgramId, "size")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        currentSession?.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = currentSession ?: return
        if (cameraTextureId != -1) session.setCameraTextureName(cameraTextureId)
        session.setDisplayGeometry(Surface.ROTATION_0, viewportWidth, viewportHeight)

        try {
            val frame = session.update()

            // --- MISE A JOUR DU STATUT (Correctif) ---
            if (!isReady) {
                isReady = true
                uiMessage = "✅ PRÊT. Visez le coin A et CLIQUEZ."
            }

            // --- LOGIQUE DE CAPTURE ---
            if (shouldCapture) {
                val hits = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
                val hit = hits.firstOrNull()

                if (hit != null) {
                    val anchor = hit.createAnchor()

                    if (anchorA == null) {
                        anchorA = anchor
                        uiMessage = "✅ A (Vert) fixé.\nVisez B et cliquez..."
                    }
                    else if (anchorB == null) {
                        anchorB = anchor
                        val dist = calculateDistance(anchorA!!, anchorB!!)
                        uiMessage = "🏁 RÉSULTAT : %.1f cm\n(Cliquez encore pour Reset)".format(dist)
                    }
                    else {
                        // RESET
                        anchorA?.detach()
                        anchorB?.detach()
                        anchorA = anchor
                        anchorB = null
                        uiMessage = "✅ Nouveau Point A fixé.\nVisez B..."
                    }
                } else {
                    uiMessage = "⚠️ RIEN DÉTECTÉ AU CENTRE.\nVisez un détail contrasté !"
                }
                shouldCapture = false
            }

            // DESSIN FOND
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            frame.transformDisplayUvCoords(bgTexCoords, bgTransformedTexCoords)
            GLES20.glUseProgram(bgProgramId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glUniform1i(bgTextureUniform, 0)
            GLES20.glEnableVertexAttribArray(bgPositionAttrib)
            GLES20.glVertexAttribPointer(bgPositionAttrib, 2, GLES20.GL_FLOAT, false, 0, bgVertices)
            GLES20.glEnableVertexAttribArray(bgTexCoordAttrib)
            GLES20.glVertexAttribPointer(bgTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, bgTransformedTexCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(bgPositionAttrib)
            GLES20.glDisableVertexAttribArray(bgTexCoordAttrib)

            // DESSIN POINTS
            val camera = frame.camera
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
            camera.getViewMatrix(viewMatrix, 0)

            // Nuage de points (Aide visuelle)
            val pointCloud = frame.acquirePointCloud()
            if (pointCloud.points.remaining() > 0) {
                 Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
                 GLES20.glUseProgram(pointProgramId)
                 GLES20.glUniform4f(pointColorUniform, 1f, 1f, 0f, 0.5f)
                 GLES20.glUniform1f(pointSizeUniform, 10.0f)
                 GLES20.glUniformMatrix4fv(pointMvpUniform, 1, false, mvpMatrix, 0)
                 GLES20.glEnableVertexAttribArray(pointPositionAttrib)
                 GLES20.glVertexAttribPointer(pointPositionAttrib, 4, GLES20.GL_FLOAT, false, 16, pointCloud.points)
                 GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCloud.points.remaining() / 4)
                 GLES20.glDisableVertexAttribArray(pointPositionAttrib)
            }
            pointCloud.release()

            // Points A et B
            GLES20.glUseProgram(pointProgramId)
            GLES20.glUniform1f(pointSizeUniform, 1.0f)
            GLES20.glEnableVertexAttribArray(pointPositionAttrib)
            GLES20.glVertexAttribPointer(pointPositionAttrib, 3, GLES20.GL_FLOAT, false, 0, measurePointVertices)

            anchorA?.let { drawAnchor(it, 0f, 1f, 0f) } // Vert
            anchorB?.let { drawAnchor(it, 1f, 0f, 0f) } // Rouge

            GLES20.glDisableVertexAttribArray(pointPositionAttrib)

        } catch (e: Exception) { }
    }

    private fun drawAnchor(anchor: Anchor, r: Float, g: Float, b: Float) {
        if (anchor.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            anchor.pose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(pointMvpUniform, 1, false, mvpMatrix, 0)
            GLES20.glUniform4f(pointColorUniform, r, g, b, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 8)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        }
    }

    private fun calculateDistance(a: Anchor, b: Anchor): Float {
        val dx = a.pose.tx() - b.pose.tx()
        val dy = a.pose.ty() - b.pose.ty()
        val dz = a.pose.tz() - b.pose.tz()
        return sqrt(dx*dx + dy*dy + dz*dz) * 100
    }

    private fun loadProgram(v: String, f: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER); GLES20.glShaderSource(vs, v); GLES20.glCompileShader(vs)
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER); GLES20.glShaderSource(fs, f); GLES20.glCompileShader(fs)
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        return p
    }
}
