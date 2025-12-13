package com.piink.proto

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private var arCoreSession: Session? = null
    // On garde le même nom de fichier Renderer pour simplifier, 
    // mais il va afficher des points maintenant.
    private val renderer = DepthRenderer(this)

    private val CAMERA_PERMISSION_CODE = 100
    private val TAG = "PiinkProto_Main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        checkAndRequestCameraPermission()
    }

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            initializeARCore()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeARCore()
        } else {
            Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeARCore() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isSupported) {
                arCoreSession = Session(this)

                // CONFIGURATION STANDARD (Optimisée pour le Tracking)
                val config = Config(arCoreSession)
                config.focusMode = Config.FocusMode.AUTO
                // On désactive la Depth pour économiser le CPU puisqu'elle ne marche pas
                config.depthMode = Config.DepthMode.DISABLED 
                
                arCoreSession?.configure(config)
                
                renderer.currentSession = arCoreSession
                arCoreSession?.resume()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur Init", e)
        }
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
        try {
            arCoreSession?.resume()
        } catch (e: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        arCoreSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arCoreSession?.close()
    }
}
