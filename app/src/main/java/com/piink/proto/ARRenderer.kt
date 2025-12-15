package com.piink.proto

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ARRenderer(val context: Context) : GLSurfaceView.Renderer {

    var currentSession: Session? = null
    var uiMessage = "Initialisation..."
    private val anchors = mutableListOf<Anchor>() // Liste des points (Max 2)
    private var shouldCapture = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lastDistance = ""

    // --- VARIABLES OPENGL ---
    
    // 1. Fond Caméra
    private var textureId = -1
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private var bgProgram: Int = 0
    private var bgPosHandle: Int = 0
    private var bgTexHandle: Int = 0
    private var bgTexUniform: Int = 0

    // 2. Croix (Ancre) & Ligne de mesure
    private val crossVertices = floatArrayOf(
        -0.05f, 0.0f,  0.0f,  0.05f, 0.0f,  0.0f, 
         0.0f,  0.0f, -0.05f,  0.0f,  0.0f,  0.05f
    )
    private lateinit var crossBuffer: FloatBuffer
    private var crossProgram: Int = 0
    private var crossPosHandle: Int = 0
    private var crossColorHandle: Int = 0
    private var crossMVPHandle: Int = 0
    
    // 3. Nuage de points (Feedback visuel)
    private var pointProgram: Int = 0
    private var pointPosHandle: Int = 0
    private var pointSizeHandle: Int = 0
    private var pointMVPHandle: Int = 0

    // Matrices
    private val projMtx = FloatArray(16)
    private val viewMtx = FloatArray(16)
    private val modelMtx = FloatArray(16)
    private val mvpMtx = FloatArray(16)
    private val identityMtx = FloatArray(16) // Pour dessiner la ligne entre 2 points

    // Couleurs
    private val colorWhite = floatArrayOf(1f, 1f, 1f, 1f)
    private val colorGreen = floatArrayOf(0f, 1f, 0f, 1f) // Pour la ligne de mesure

    // Shaders
    private val vShaderCam = "attribute vec4 vPos; attribute vec2 vTex; varying vec2 fTex; void main(){gl_Position=vPos; fTex=vTex;}"
    private val fShaderCam = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 fTex; uniform samplerExternalOES sTex; void main(){gl_FragColor=texture2D(sTex,fTex);}"
    
    private val vShaderSimple = "uniform mat4 uMVP; attribute vec4 vPos; uniform float uPSize; void main(){gl_Position=uMVP*vPos; gl_PointSize=uPSize;}"
    private val fShaderColor = "precision mediump float; uniform vec4 vCol; void main(){gl_FragColor=vCol;}"

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        Matrix.setIdentityM(identityMtx, 0)

        // INIT TEXTURE CAMERA
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST.toFloat())
        currentSession?.setCameraTextureName(textureId)

        // INIT BUFFERS
        quadVertices = makeBuffer(floatArrayOf(-1f, -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f))
        quadTexCoords = makeBuffer(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        crossBuffer = makeBuffer(crossVertices)

        // INIT PROGRAMMES
        bgProgram = createProgram(vShaderCam, fShaderCam)
        bgPosHandle = GLES20.glGetAttribLocation(bgProgram, "vPos")
        bgTexHandle = GLES20.glGetAttribLocation(bgProgram, "vTex")
        bgTexUniform = GLES20.glGetUniformLocation(bgProgram, "sTex")

        crossProgram = createProgram(vShaderSimple, fShaderColor)
        crossPosHandle = GLES20.glGetAttribLocation(crossProgram, "vPos")
        crossMVPHandle = GLES20.glGetUniformLocation(crossProgram, "uMVP")
        crossColorHandle = GLES20.glGetUniformLocation(crossProgram, "vCol")
        
        pointProgram = createProgram(vShaderSimple, fShaderColor)
        pointPosHandle = GLES20.glGetAttribLocation(pointProgram, "vPos")
        pointMVPHandle = GLES20.glGetUniformLocation(pointProgram, "uMVP")
        pointSizeHandle = GLES20.glGetUniformLocation(pointProgram, "uPSize")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        currentSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (currentSession == null) return

        try {
            if (textureId != -1) currentSession!!.setCameraTextureName(textureId)
            val frame = currentSession!!.update()
            val camera = frame.camera

            handleTap(frame, camera)
            
            // 1. DESSIN CAMERA
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(bgProgram)
            
            val uvs = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            frame.transformDisplayUvCoords(quadTexCoords, uvs)
            
            GLES20.glVertexAttribPointer(bgPosHandle, 3, GLES20.GL_FLOAT, false, 0, quadVertices)
            GLES20.glVertexAttribPointer(bgTexHandle, 2, GLES20.GL_FLOAT, false, 0, uvs)
            GLES20.glEnableVertexAttribArray(bgPosHandle)
            GLES20.glEnableVertexAttribArray(bgTexHandle)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(bgTexUniform, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // MATRICES 3D
            camera.getProjectionMatrix(projMtx, 0, 0.1f, 100f)
            camera.getViewMatrix(viewMtx, 0)
            Matrix.multiplyMM(mvpMtx, 0, projMtx, 0, viewMtx, 0) // VP Matrix

            // 2. DESSIN POINTS (FEEDBACK JAUNE)
            GLES20.glUseProgram(pointProgram)
            val pointCloud = frame.acquirePointCloud()
            if (pointCloud.points != null) {
                 GLES20.glUniformMatrix4fv(pointMVPHandle, 1, false, mvpMtx, 0)
                 GLES20.glUniform1f(pointSizeHandle, 15.0f)
                 GLES20.glUniform4fv(GLES20.glGetUniformLocation(pointProgram, "vCol"), 1, floatArrayOf(1f, 0.8f, 0f, 1f), 0) // Jaune
                 GLES20.glVertexAttribPointer(pointPosHandle, 4, GLES20.GL_FLOAT, false, 16, pointCloud.points)
                 GLES20.glEnableVertexAttribArray(pointPosHandle)
                 GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCloud.points.remaining() / 4)
            }
            pointCloud.release()

            // 3. DESSIN DES ANCRES (CROIX)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(crossProgram)
            
            for (anchor in anchors) {
                if (anchor.trackingState != TrackingState.TRACKING) continue
                anchor.pose.toMatrix(modelMtx, 0)
                Matrix.multiplyMM(mvpMtx, 0, projMtx, 0, viewMtx, 0) 
                Matrix.multiplyMM(mvpMtx, 0, mvpMtx, 0, modelMtx, 0)
                
                GLES20.glUniformMatrix4fv(crossMVPHandle, 1, false, mvpMtx, 0)
                GLES20.glUniform4fv(crossColorHandle, 1, colorWhite, 0)
                GLES20.glVertexAttribPointer(crossPosHandle, 3, GLES20.GL_FLOAT, false, 0, crossBuffer)
                GLES20.glEnableVertexAttribArray(crossPosHandle)
                GLES20.glLineWidth(8f)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 4)
            }

            // 4. DESSIN DE LA LIGNE DE MESURE (Si 2 points)
            if (anchors.size == 2 && anchors[0].trackingState == TrackingState.TRACKING && anchors[1].trackingState == TrackingState.TRACKING) {
                drawLineBetweenAnchors(anchors[0], anchors[1])
            }

        } catch (e: Exception) { Log.e("RENDERER", "Err: " + e.message) }
    }

    private fun drawLineBetweenAnchors(a1: Anchor, a2: Anchor) {
        val p1 = a1.pose
        val p2 = a2.pose
        val lineCoords = floatArrayOf(
            p1.tx(), p1.ty(), p1.tz(),
            p2.tx(), p2.ty(), p2.tz()
        )
        val lineBuff = makeBuffer(lineCoords)
        
        // On utilise la matrice VP (sans modele) car les points sont deja en coordonnées monde
        Matrix.multiplyMM(mvpMtx, 0, projMtx, 0, viewMtx, 0)
        
        GLES20.glUniformMatrix4fv(crossMVPHandle, 1, false, mvpMtx, 0)
        GLES20.glUniform4fv(crossColorHandle, 1, colorGreen, 0) // Ligne VERTE
        GLES20.glVertexAttribPointer(crossPosHandle, 3, GLES20.GL_FLOAT, false, 0, lineBuff)
        GLES20.glEnableVertexAttribArray(crossPosHandle)
        GLES20.glLineWidth(10f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
    }

    private fun handleTap(frame: com.google.ar.core.Frame, camera: com.google.ar.core.Camera) {
        if (shouldCapture && viewportWidth > 0) {
            
            // LOGIQUE DE CYCLE : A -> B -> RESET
            if (anchors.size >= 2) {
                // RESET
                anchors.forEach { it.detach() }
                anchors.clear()
                uiMessage = "🗑️ Reset. Placez Point A."
                // On continue pour placer le Point A tout de suite (optionnel, plus fluide)
            }

            val hits = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
            for (hit in hits) {
                val trackable = hit.trackable
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                    (trackable is com.google.ar.core.Point) ||
                    (trackable is InstantPlacementPoint)) {
                    
                    val newAnchor = hit.createAnchor()
                    anchors.add(newAnchor)
                    
                    if (anchors.size == 1) {
                        uiMessage = "📍 Point A placé. Visez Point B."
                    } else if (anchors.size == 2) {
                        val dist = calculateDistance(anchors[0], anchors[1])
                        uiMessage = "📏 DISTANCE : %.2f m".format(dist)
                        lastDistance = uiMessage
                    }
                    break
                }
            }
            shouldCapture = false
        }
        
        // UI Message Persistant pour la distance
        if (anchors.size == 2) {
            uiMessage = lastDistance
        } else if (anchors.isEmpty() && uiMessage.contains("Distance")) {
            uiMessage = "🎯 Visez et Cliquez"
        }
    }

    private fun calculateDistance(a1: Anchor, a2: Anchor): Float {
        val dx = a1.pose.tx() - a2.pose.tx()
        val dy = a1.pose.ty() - a2.pose.ty()
        val dz = a1.pose.tz() - a2.pose.tz()
        return sqrt(dx*dx + dy*dy + dz*dz)
    }

    fun triggerCapture() { shouldCapture = true }

    private fun makeBuffer(arr: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder())
        return bb.asFloatBuffer().put(arr).apply { position(0) }
    }

    private fun createProgram(v: String, f: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, v); GLES20.glCompileShader(it) }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, f); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it) }
    }
}
