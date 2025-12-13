package com.piink.proto

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DepthRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val TAG = "DepthRenderer"

    // La texture caméra utilise un type spécial sous Android (OES)
    private var cameraTextureId = -1

    // Shader
    private var programId = -1
    private var positionAttrib = -1
    private var texCoordAttrib = -1
    private var cameraTextureUniform = -1

    // Géométrie (Un simple rectangle plein écran)
    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Session ARCore
    var currentSession: Session? = null
    private var viewportWidth = 1
    private var viewportHeight = 1

    init {
        // Coordonnées du rectangle (X, Y)
        quadVertices.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        // Coordonnées de texture (U, V)
        quadTexCoords.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f) // Gris foncé au lieu de noir si vide

        // 1. Création de la texture Caméra (OES)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        // On lie la texture au type spécial GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // 2. Shaders (Modifiés pour afficher la CAMÉRA et non la profondeur)
        val vertexShaderCode = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        // NOTE: samplerExternalOES est obligatoire pour le flux caméra Android
        val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_CameraTexture; 
            varying vec2 v_TexCoord;
            
            void main() {
                // On affiche simplement la couleur de la caméra
                gl_FragColor = texture2D(u_CameraTexture, v_TexCoord);
            }
        """

        programId = loadProgram(vertexShaderCode, fragmentShaderCode)
        positionAttrib = GLES20.glGetAttribLocation(programId, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(programId, "a_TexCoord")
        cameraTextureUniform = GLES20.glGetUniformLocation(programId, "u_CameraTexture")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        currentSession?.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val session = currentSession ?: return

        // On dit à ARCore d'utiliser notre texture créée plus haut
        if (cameraTextureId != -1) {
            session.setCameraTextureName(cameraTextureId)
        }

        session.setDisplayGeometry(Surface.ROTATION_0, viewportWidth, viewportHeight)

        try {
            // Mettre à jour la session remplit la texture avec la nouvelle image caméra
            val frame = session.update()

            // --- DESSIN DU FLUX CAMÉRA ---
            GLES20.glUseProgram(programId)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glUniform1i(cameraTextureUniform, 0)

            GLES20.glEnableVertexAttribArray(positionAttrib)
            GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

            // Pour bien faire, on devrait transformer les coordonnées UV avec frame.transformDisplayUvCoords
            // Mais pour ce test, on utilise les coordonnées brutes.
            GLES20.glEnableVertexAttribArray(texCoordAttrib)
            GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionAttrib)
            GLES20.glDisableVertexAttribArray(texCoordAttrib)

        } catch (e: Exception) {
            // Ignorer les erreurs frame.update() si la session est en pause
        }
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