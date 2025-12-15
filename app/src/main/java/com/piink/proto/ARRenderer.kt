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
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

// Structure pour transférer les infos de texte à l'activité
data class ARLabel(val x: Float, val y: Float, val text: String, val type: Int)
// type 0 = Lettre (A,B..), 1 = Mesure (1.5m)

class ARRenderer(val context: Context) : GLSurfaceView.Renderer {

    var currentSession: Session? = null
    var uiMessage = "Initialisation..."
    
    // LISTE DES LABELS (Partagée avec MainActivity)
    val labelsToDraw = CopyOnWriteArrayList<ARLabel>()

    private val anchors = mutableListOf<Anchor>() 
    private var shouldCapture = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lastInfo = ""

    // --- OPENGL ---
    private var textureId = -1
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private var bgProgram: Int = 0
    private var bgPosHandle: Int = 0
    private var bgTexHandle: Int = 0
    private var bgTexUniform: Int = 0

    private val crossVertices = floatArrayOf(
        -0.05f, 0.0f,  0.0f,  0.05f, 0.0f,  0.0f, 
         0.0f,  0.0f, -0.05f,  0.0f,  0.0f,  0.05f
    )
    private lateinit var crossBuffer: FloatBuffer
    private var crossProgram: Int = 0
    private var crossPosHandle: Int = 0
    private var crossColorHandle: Int = 0
    private var crossMVPHandle: Int = 0
    
    private var pointProgram: Int = 0
    private var pointPosHandle: Int = 0
    private var pointSizeHandle: Int = 0
    private var pointMVPHandle: Int = 0

    // Matrices
    private val projMtx = FloatArray(16)
    private val viewMtx = FloatArray(16)
    private val mvpMtx = FloatArray(16) // View * Projection
    private val modelMtx = FloatArray(16)
    private val finalMtx = FloatArray(16) // MVP * Model

    private val colorWhite = floatArrayOf(1f, 1f, 1f, 1f)
    private val colorCyan = floatArrayOf(0f, 1f, 1f, 1f)

    private val vShaderCam = "attribute vec4 vPos; attribute vec2 vTex; varying vec2 fTex; void main(){gl_Position=vPos; fTex=vTex;}"
    private val fShaderCam = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 fTex; uniform samplerExternalOES sTex; void main(){gl_FragColor=texture2D(sTex,fTex);}"
    private val vShaderSimple = "uniform mat4 uMVP; attribute vec4 vPos; uniform float uPSize; void main(){gl_Position=uMVP*vPos; gl_PointSize=uPSize;}"
    private val fShaderColor = "precision mediump float; uniform vec4 vCol; void main(){gl_FragColor=vCol;}"

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST.toFloat())
        currentSession?.setCameraTextureName(textureId)

        quadVertices = makeBuffer(floatArrayOf(-1f, -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f))
        quadTexCoords = makeBuffer(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        crossBuffer = makeBuffer(crossVertices)

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

            // CALCUL MATRICES CAMERA
            camera.getProjectionMatrix(projMtx, 0, 0.1f, 100f)
            camera.getViewMatrix(viewMtx, 0)
            Matrix.multiplyMM(mvpMtx, 0, projMtx, 0, viewMtx, 0) // VP Matrix

            // 2. POINTS JAUNES
            GLES20.glUseProgram(pointProgram)
            val pointCloud = frame.acquirePointCloud()
            if (pointCloud.points != null) {
                 GLES20.glUniformMatrix4fv(pointMVPHandle, 1, false, mvpMtx, 0)
                 GLES20.glUniform1f(pointSizeHandle, 10.0f)
                 GLES20.glUniform4fv(GLES20.glGetUniformLocation(pointProgram, "vCol"), 1, floatArrayOf(1f, 0.8f, 0f, 1f), 0)
                 GLES20.glVertexAttribPointer(pointPosHandle, 4, GLES20.GL_FLOAT, false, 16, pointCloud.points)
                 GLES20.glEnableVertexAttribArray(pointPosHandle)
                 GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCloud.points.remaining() / 4)
            }
            pointCloud.release()

            // 3. ANCRES (CROIX) ET PREPARATION LABELS
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(crossProgram)
            
            // On vide la liste des labels pour cette frame
            val newLabels = ArrayList<ARLabel>()
            val letters = arrayOf("A", "B", "C", "D")

            for ((index, anchor) in anchors.withIndex()) {
                if (anchor.trackingState != TrackingState.TRACKING) continue
                
                // Dessin 3D
                anchor.pose.toMatrix(modelMtx, 0)
                Matrix.multiplyMM(finalMtx, 0, mvpMtx, 0, modelMtx, 0)
                
                GLES20.glUniformMatrix4fv(crossMVPHandle, 1, false, finalMtx, 0)
                GLES20.glUniform4fv(crossColorHandle, 1, colorWhite, 0)
                GLES20.glVertexAttribPointer(crossPosHandle, 3, GLES20.GL_FLOAT, false, 0, crossBuffer)
                GLES20.glEnableVertexAttribArray(crossPosHandle)
                GLES20.glLineWidth(5f)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 4)

                // Calcul Position 2D pour le Label (Lettre)
                val screenPos = worldToScreen(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz(), mvpMtx)
                if (screenPos != null) {
                    val txt = if (index < 4) letters[index] else "?"
                    newLabels.add(ARLabel(screenPos[0], screenPos[1], txt, 0))
                }
            }
            
            // 4. LIGNES + LABELS LONGUEURS
            if (anchors.size > 1) {
                drawPolylineAndCalcLengths(newLabels)
            }
            
            // Mise à jour Thread-Safe des labels pour l'UI
            labelsToDraw.clear()
            labelsToDraw.addAll(newLabels)

        } catch (e: Exception) { Log.e("RENDERER", "Err: " + e.message) }
    }

    private fun drawPolylineAndCalcLengths(labelsList: ArrayList<ARLabel>) {
        val coordsList = ArrayList<Float>()
        val anchorList = ArrayList<Anchor>()
        
        for (a in anchors) {
            if (a.trackingState == TrackingState.TRACKING) {
                coordsList.add(a.pose.tx()); coordsList.add(a.pose.ty()); coordsList.add(a.pose.tz())
                anchorList.add(a)
            }
        }
        
        // Fermeture boucle
        if (anchors.size == 4 && anchors[0].trackingState == TrackingState.TRACKING) {
            val a0 = anchors[0]
            coordsList.add(a0.pose.tx()); coordsList.add(a0.pose.ty()); coordsList.add(a0.pose.tz())
            anchorList.add(a0)
        }

        if (coordsList.isEmpty()) return

        // Dessin Ligne
        val lineVertices = FloatArray(coordsList.size)
        for (i in coordsList.indices) lineVertices[i] = coordsList[i]
        val lineBuff = makeBuffer(lineVertices)
        
        GLES20.glUniformMatrix4fv(crossMVPHandle, 1, false, mvpMtx, 0)
        GLES20.glUniform4fv(crossColorHandle, 1, colorCyan, 0)
        GLES20.glVertexAttribPointer(crossPosHandle, 3, GLES20.GL_FLOAT, false, 0, lineBuff)
        GLES20.glEnableVertexAttribArray(crossPosHandle)
        GLES20.glLineWidth(10f)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, lineVertices.size / 3)

        // Calcul des Labels de Longueur (Milieu des segments)
        for (i in 0 until anchorList.size - 1) {
            val a1 = anchorList[i]
            val a2 = anchorList[i+1]
            
            val dist = calculateDistance(a1, a2)
            
            // Position milieu 3D
            val midX = (a1.pose.tx() + a2.pose.tx()) / 2
            val midY = (a1.pose.ty() + a2.pose.ty()) / 2
            val midZ = (a1.pose.tz() + a2.pose.tz()) / 2
            
            val screenPos = worldToScreen(midX, midY, midZ, mvpMtx)
            if (screenPos != null) {
                labelsList.add(ARLabel(screenPos[0], screenPos[1], "%.2fm".format(dist), 1))
            }
        }
    }
    
    // MATHEMATIQUES : Projection 3D -> 2D
    private fun worldToScreen(x: Float, y: Float, z: Float, mvp: FloatArray): FloatArray? {
        val res = FloatArray(4)
        // Multiplication Vecteur (x,y,z,1) par Matrice MVP
        Matrix.multiplyMV(res, 0, mvp, 0, floatArrayOf(x, y, z, 1f), 0)
        
        // Si w < 0, le point est derrière la caméra
        if (res[3] <= 0) return null
        
        // Normalisation (-1 à 1)
        val ndcX = res[0] / res[3]
        val ndcY = res[1] / res[3]
        
        // Mapping vers l'écran (0 à width, 0 à height)
        val screenX = (ndcX + 1) / 2 * viewportWidth
        val screenY = (1 - ndcY) / 2 * viewportHeight // Attention, Y inversé en GL ?
        // Android Canvas Y=0 en haut, GL Y=0 en bas.
        // On renvoie coord Android (Y inversé par rapport à GL)
        return floatArrayOf(screenX, viewportHeight - screenY)
    }

    private fun handleTap(frame: com.google.ar.core.Frame, camera: com.google.ar.core.Camera) {
        if (shouldCapture && viewportWidth > 0) {
            if (anchors.size >= 4) {
                anchors.forEach { it.detach() }
                anchors.clear()
                uiMessage = "🗑️ Reset. Placez Point A."
            }
            val hits = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
            for (hit in hits) {
                val trackable = hit.trackable
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                    (trackable is com.google.ar.core.Point) ||
                    (trackable is InstantPlacementPoint)) {
                    
                    anchors.add(hit.createAnchor())
                    if (anchors.size < 3) uiMessage = "Point ${anchors.size} placé."
                    else if (anchors.size == 3) uiMessage = "Triangle: %.2f m²".format(calculateTriangleArea(anchors[0], anchors[1], anchors[2]))
                    else if (anchors.size == 4) {
                        val t1 = calculateTriangleArea(anchors[0], anchors[1], anchors[2])
                        val t2 = calculateTriangleArea(anchors[0], anchors[2], anchors[3])
                        uiMessage = "🟦 SURFACE : %.2f m²".format(t1 + t2)
                        lastInfo = uiMessage
                    }
                    break
                }
            }
            shouldCapture = false
        }
        if (anchors.size >= 4) uiMessage = lastInfo
        else if (anchors.isEmpty() && uiMessage.contains("m²")) uiMessage = "🎯 Visez et Cliquez"
    }

    private fun calculateDistance(a1: Anchor, a2: Anchor): Float {
        val dx = a1.pose.tx() - a2.pose.tx()
        val dy = a1.pose.ty() - a2.pose.ty()
        val dz = a1.pose.tz() - a2.pose.tz()
        return sqrt(dx*dx + dy*dy + dz*dz)
    }

    private fun calculateTriangleArea(p0: Anchor, p1: Anchor, p2: Anchor): Float {
        val abX = p1.pose.tx() - p0.pose.tx(); val abY = p1.pose.ty() - p0.pose.ty(); val abZ = p1.pose.tz() - p0.pose.tz()
        val acX = p2.pose.tx() - p0.pose.tx(); val acY = p2.pose.ty() - p0.pose.ty(); val acZ = p2.pose.tz() - p0.pose.tz()
        val cpX = abY * acZ - abZ * acY; val cpY = abZ * acX - abX * acZ; val cpZ = abX * acY - abY * acX
        return sqrt(cpX*cpX + cpY*cpY + cpZ*cpZ) / 2.0f
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
