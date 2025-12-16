package com.piink.proto

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Plane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ARRenderer(val context: Context) : GLSurfaceView.Renderer {

    var currentSession: Session? = null
    @Volatile var uiMessage = "Scannez le sol..."
    
    private var viewportWidth = 0
    private var viewportHeight = 0
    
    private var detectedPlane: Plane? = null

    // OPENGL
    private var textureId = -1
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    
    private var bgProgram: Int = 0
    private var bgPosHandle: Int = 0
    private var bgTexHandle: Int = 0
    private var bgTexUniform: Int = 0

    private var standardProgram: Int = 0
    private var posHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpHandle: Int = 0

    private val projMtx = FloatArray(16)
    private val viewMtx = FloatArray(16)
    private val mvpMtx = FloatArray(16)
    private val planeModelMtx = FloatArray(16) 

    private val colorContour = floatArrayOf(0f, 1f, 0f, 1f)    
    private val colorRect = floatArrayOf(1f, 0f, 0f, 1f)       
    private val colorFill = floatArrayOf(1f, 0f, 0f, 0.3f)     

    // SHADERS
    private val vShaderCam = "attribute vec4 vPos; attribute vec2 vTex; varying vec2 fTex; void main(){gl_Position=vPos; fTex=vTex;}"
    private val fShaderCam = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 fTex; uniform samplerExternalOES sTex; void main(){gl_FragColor=texture2D(sTex,fTex);}"
    private val vShaderSimple = "uniform mat4 uMVP; attribute vec4 vPos; void main(){gl_Position=uMVP*vPos;}"
    private val fShaderSimple = "precision mediump float; uniform vec4 vCol; void main(){gl_FragColor=vCol;}"

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST.toFloat())
        currentSession?.setCameraTextureName(textureId)

        quadVertices = makeBuffer(floatArrayOf(-1f,-1f,0f, -1f,1f,0f, 1f,-1f,0f, 1f,1f,0f))
        quadTexCoords = makeBuffer(floatArrayOf(0f,1f, 0f,0f, 1f,1f, 1f,0f))

        bgProgram = createProgram(vShaderCam, fShaderCam)
        bgPosHandle = GLES20.glGetAttribLocation(bgProgram, "vPos")
        bgTexHandle = GLES20.glGetAttribLocation(bgProgram, "vTex")
        bgTexUniform = GLES20.glGetUniformLocation(bgProgram, "sTex")

        standardProgram = createProgram(vShaderSimple, fShaderSimple)
        posHandle = GLES20.glGetAttribLocation(standardProgram, "vPos")
        mvpHandle = GLES20.glGetUniformLocation(standardProgram, "uMVP")
        colorHandle = GLES20.glGetUniformLocation(standardProgram, "vCol")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        currentSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Nettoyage standard
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        if (currentSession == null) return

        try {
            // Mise à jour texture caméra
            if (textureId != -1) currentSession!!.setCameraTextureName(textureId)
            val frame = currentSession!!.update()
            val camera = frame.camera

            // --- ETAPE 1 : DESSIN CAMERA (PRIORITE ABSOLUE) ---
            // On le fait avant tout calcul complexe pour garantir qu'on voit quelque chose
            GLES20.glDisable(GLES20.GL_DEPTH_TEST) // Fond d'écran
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

            // Important : On désactive les attributs pour ne pas polluer la suite
            GLES20.glDisableVertexAttribArray(bgPosHandle)
            GLES20.glDisableVertexAttribArray(bgTexHandle)
            // --------------------------------------------------

            // --- ETAPE 2 : LOGIQUE METIER ---
            findPlaneUnderCrosshair(frame)
            
            camera.getProjectionMatrix(projMtx, 0, 0.1f, 100f)
            camera.getViewMatrix(viewMtx, 0)
            
            // --- ETAPE 3 : DESSIN SURFACE ---
            if (detectedPlane != null && detectedPlane!!.trackingState == TrackingState.TRACKING) {
                // On réactive le Depth Test pour la 3D
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                drawDetectedPlane(detectedPlane!!)
            } else {
                 uiMessage = "Scannez le sol..."
            }

        } catch (e: Exception) { 
            // Si ça plante ici, la vidéo est déjà dessinée, donc pas d'écran noir !
            Log.e("RENDERER", "Erreur Rendu: " + e.message) 
        }
    }

    private fun findPlaneUnderCrosshair(frame: com.google.ar.core.Frame) {
        if (viewportWidth == 0) return
        val hits = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
        var found: Plane? = null
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                found = trackable
                break
            }
        }
        detectedPlane = found
    }

    private fun drawDetectedPlane(plane: Plane) {
        val polygon = plane.polygon 
        if (polygon == null || polygon.remaining() < 6) return

        plane.centerPose.toMatrix(planeModelMtx, 0)
        Matrix.multiplyMM(mvpMtx, 0, viewMtx, 0, planeModelMtx, 0)
        Matrix.multiplyMM(mvpMtx, 0, projMtx, 0, mvpMtx, 0)

        // Extraction points
        val vertexCount = polygon.remaining() / 2
        val rawCoords = FloatArray(vertexCount * 3)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        
        polygon.rewind()
        var i = 0
        while (polygon.hasRemaining()) {
            val x = polygon.get()
            val z = polygon.get()
            rawCoords[i*3] = x; rawCoords[i*3+1] = 0f; rawCoords[i*3+2] = z
            minX = min(minX, x); maxX = max(maxX, x)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
            i++
        }
        
        // 1. Dessin Contour Vert
        val polyBuff = makeBuffer(rawCoords)
        GLES20.glUseProgram(standardProgram)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMtx, 0)
        GLES20.glUniform4fv(colorHandle, 1, colorContour, 0) 
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, polyBuff)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glLineWidth(5f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(posHandle)

        // 2. Dessin Rectangle Rouge
        val width = maxX - minX
        val depth = maxZ - minZ
        val area = width * depth
        
        val rectCoords = floatArrayOf(minX,0.01f,minZ, maxX,0.01f,minZ, maxX,0.01f,maxZ, minX,0.01f,maxZ)
        val rectBuff = makeBuffer(rectCoords)
        
        GLES20.glUniform4fv(colorHandle, 1, colorFill, 0) 
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, rectBuff)
        GLES20.glEnableVertexAttribArray(posHandle)
        
        // Astuce visibilité : Pas de Depth Test pour le remplissage
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        
        GLES20.glUniform4fv(colorHandle, 1, colorRect, 0)
        GLES20.glLineWidth(10f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        
        // Reset states
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisableVertexAttribArray(posHandle)

        updateInfo(width, depth, area)
    }

    private fun updateInfo(w: Float, h: Float, area: Float) {
        val text = "AUTO-SCAN\nLarg: %.2f m\nLong: %.2f m\nSURFACE: %.2f m²".format(w, h, area)
        if (abs(area - lastArea) > 0.05f) { 
            uiMessage = text
            lastArea = area
        }
    }
    private var lastArea = 0f

    fun triggerCapture() { /* Vide pour l'instant */ }

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
