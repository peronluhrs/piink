package com.piink.proto

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DepthRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- VARIABLES FOND ---
    private var cameraTextureId = -1
    private var bgProgramId = -1
    private var bgPositionAttrib = -1
    private var bgTexCoordAttrib = -1
    private var bgTextureUniform = -1
    private val bgVertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val bgTexCoords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val bgTransformedTexCoords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // --- VARIABLES CUBE ---
    private var cubeProgramId = -1
    private var cubePositionAttrib = -1
    private var cubeColorAttrib = -1
    private var cubeMvpUniform = -1
    
    // Données du Cube
    private val cubeVertices: FloatBuffer
    private val cubeColors: FloatBuffer
    private val cubeIndices: ShortBuffer

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    var currentSession: Session? = null
    private var viewportWidth = 1
    private var viewportHeight = 1

    init {
        // Init Fond
        bgVertices.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        bgTexCoords.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)).position(0)

        // Init Cube (Un cube de 20cm de côté)
        val s = 0.1f // taille demi-coté (10cm)
        
        // 8 Sommets (X, Y, Z)
        val vertices = floatArrayOf(
            -s, -s,  s,  s, -s,  s,  s,  s,  s, -s,  s,  s, // Face avant
            -s, -s, -s, -s,  s, -s,  s,  s, -s,  s, -s, -s  // Face arrière
        )
        // Couleurs (R, G, B, A) pour chaque sommet
        val colors = floatArrayOf(
            0f,1f,0f,1f, 1f,0f,0f,1f, 1f,1f,0f,1f, 0f,0f,1f,1f,
            0f,1f,1f,1f, 0f,0f,1f,1f, 1f,0f,1f,1f, 1f,1f,1f,1f
        )
        // Indices pour dessiner les triangles
        val indices = shortArrayOf(
            0,1,2, 0,2,3, // Avant
            1,7,6, 1,6,2, // Droite
            7,4,5, 7,5,6, // Arrière
            4,0,3, 4,3,5, // Gauche
            3,2,6, 3,6,5, // Haut
            4,7,1, 4,1,0  // Bas
        )

        cubeVertices = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
        cubeVertices.position(0)
        
        cubeColors = ByteBuffer.allocateDirect(colors.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(colors)
        cubeColors.position(0)
        
        cubeIndices = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(indices)
        cubeIndices.position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 1. Texture Caméra
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // 2. Shader Fond
        val bgV = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){gl_Position=p;v=t;}"
        val bgF = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 v; void main(){gl_FragColor=texture2D(s,v);}"
        bgProgramId = loadProgram(bgV, bgF)
        bgPositionAttrib = GLES20.glGetAttribLocation(bgProgramId, "p")
        bgTexCoordAttrib = GLES20.glGetAttribLocation(bgProgramId, "t")
        bgTextureUniform = GLES20.glGetUniformLocation(bgProgramId, "s")

        // 3. Shader Cube
        val cubeV = "uniform mat4 mvp; attribute vec4 p; attribute vec4 c; varying vec4 v; void main(){gl_Position=mvp*p;v=c;}"
        val cubeF = "precision mediump float; varying vec4 v; void main(){gl_FragColor=v;}"
        cubeProgramId = loadProgram(cubeV, cubeF)
        cubePositionAttrib = GLES20.glGetAttribLocation(cubeProgramId, "p")
        cubeColorAttrib = GLES20.glGetAttribLocation(cubeProgramId, "c")
        cubeMvpUniform = GLES20.glGetUniformLocation(cubeProgramId, "mvp")
        
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
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
            
            // --- FOND ---
            GLES20.glDepthMask(false)
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
            GLES20.glDepthMask(true)

            // --- CUBE ---
            val camera = frame.camera
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)
            
            // On place le cube à 1 mètre devant la position de départ (Z = -1.0)
            // Matrix.setIdentityM(modelMatrix, 0)
            // Matrix.translateM(modelMatrix, 0, 0f, 0f, -0.5f) // recule de 50cm
            
            // Pour ce test simple, on considère le cube à l'origine (0,0,0) du monde AR
            // La caméra va bouger autour.
            Matrix.setIdentityM(modelMatrix, 0)
            
            // Calcul final
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewProjectionMatrix, 0)

            GLES20.glUseProgram(cubeProgramId)
            GLES20.glUniformMatrix4fv(cubeMvpUniform, 1, false, modelViewProjectionMatrix, 0)
            
            GLES20.glEnableVertexAttribArray(cubePositionAttrib)
            GLES20.glVertexAttribPointer(cubePositionAttrib, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
            
            GLES20.glEnableVertexAttribArray(cubeColorAttrib)
            GLES20.glVertexAttribPointer(cubeColorAttrib, 4, GLES20.GL_FLOAT, false, 0, cubeColors)
            
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
            
            GLES20.glDisableVertexAttribArray(cubePositionAttrib)
            GLES20.glDisableVertexAttribArray(cubeColorAttrib)

        } catch (e: Exception) { }
    }

    private fun loadProgram(v: String, f: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vs, v)
        GLES20.glCompileShader(vs)
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fs, f)
        GLES20.glCompileShader(fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }
}
