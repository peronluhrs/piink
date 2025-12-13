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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DepthRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- VARIABLES CAMÉRA DE FOND ---
    private var cameraTextureId = -1
    private var bgProgramId = -1
    private var bgPositionAttrib = -1
    private var bgTexCoordAttrib = -1
    private var bgTextureUniform = -1
    private val bgVertices: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val bgTexCoords: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val bgTransformedTexCoords: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // --- VARIABLES POINTS (3D) ---
    private var pointProgramId = -1
    private var pointPositionAttrib = -1
    private var pointUniformMvp = -1
    private var pointUniformColor = -1

    // Matrices 3D
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    var currentSession: Session? = null
    private var viewportWidth = 1
    private var viewportHeight = 1

    init {
        // Init Géométrie Background (Carré plein écran)
        bgVertices.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        bgTexCoords.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 1. Initialiser la texture caméra
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // 2. Shader pour le FOND (Caméra)
        val bgVShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        val bgFShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_CameraTexture; 
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_CameraTexture, v_TexCoord);
            }
        """
        bgProgramId = loadProgram(bgVShader, bgFShader)
        bgPositionAttrib = GLES20.glGetAttribLocation(bgProgramId, "a_Position")
        bgTexCoordAttrib = GLES20.glGetAttribLocation(bgProgramId, "a_TexCoord")
        bgTextureUniform = GLES20.glGetUniformLocation(bgProgramId, "u_CameraTexture")

        // 3. Shader pour les POINTS 3D
        val pointVShader = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
                gl_PointSize = 20.0; // GROS POINTS JAUNES
            }
        """
        val pointFShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
        pointProgramId = loadProgram(pointVShader, pointFShader)
        pointPositionAttrib = GLES20.glGetAttribLocation(pointProgramId, "a_Position")
        pointUniformMvp = GLES20.glGetUniformLocation(pointProgramId, "u_ModelViewProjection")
        pointUniformColor = GLES20.glGetUniformLocation(pointProgramId, "u_Color")
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
            
            // --- 1. DESSINER LE FOND CAMÉRA ---
            GLES20.glDisable(GLES20.GL_DEPTH_TEST) // Le fond est toujours derrière
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

            // --- 2. DESSINER LE NUAGE DE POINTS ---
            val pointCloud = frame.acquirePointCloud()
            val points = pointCloud.points // Buffer brut (X, Y, Z, Confiance)
            val numPoints = pointCloud.points.remaining() / 4 

            if (numPoints > 0) {
                // Calcul des matrices 3D
                val camera = frame.camera
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                camera.getViewMatrix(viewMatrix, 0)
                Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                GLES20.glUseProgram(pointProgramId)
                
                // Couleur JAUNE FLUO
                GLES20.glUniform4f(pointUniformColor, 1.0f, 1.0f, 0.0f, 1.0f)
                
                // Matrice MVP
                GLES20.glUniformMatrix4fv(pointUniformMvp, 1, false, viewProjectionMatrix, 0)

                // Envoi des positions
                GLES20.glEnableVertexAttribArray(pointPositionAttrib)
                // Stride de 16 octets (4 floats : x, y, z, confidence)
                GLES20.glVertexAttribPointer(pointPositionAttrib, 4, GLES20.GL_FLOAT, false, 16, points)

                // Dessin des points
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)
                
                GLES20.glDisableVertexAttribArray(pointPositionAttrib)
            }
            pointCloud.release() // Important de libérer !

        } catch (e: Exception) { }
    }

    private fun loadProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
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
}
