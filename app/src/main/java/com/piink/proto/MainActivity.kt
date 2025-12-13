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
    // On instancie notre nouveau moteur de rendu
    private val renderer = DepthRenderer(this)

    private val CAMERA_PERMISSION_CODE = 100
    private val TAG = "PiinkProto_Main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        
        // Configuration OpenGL ES 2.0
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        // On demande de dessiner en continu
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

                // --- CORRECTIF 1 : FORCER 30 FPS ---
                // On crée un filtre pour demander explicitement du 30 FPS
                val filter = com.google.ar.core.CameraConfigFilter(arCoreSession)
                filter.targetFps = java.util.EnumSet.of(com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_30)

                // On récupère la liste des caméras compatibles avec ce filtre
                val cameraConfigs = arCoreSession?.getSupportedCameraConfigs(filter)

                // Si on en trouve une, on l'applique !
                if (!cameraConfigs.isNullOrEmpty()) {
                    Log.i(TAG, "Configuration Caméra forcée à 30 FPS")
                    arCoreSession?.cameraConfig = cameraConfigs[0]
                } else {
                    Log.e(TAG, "Aucune configuration 30 FPS trouvée !")
                }
                // ------------------------------------

                val config = Config(arCoreSession)

                // --- CORRECTIF 2 : DÉSACTIVER DEPTH (TEMPORAIRE) ---
                // Le calcul de profondeur peut tuer les FPS sur certains téléphones.
                // On le coupe pour tester si le tracking revient.
                //config.depthMode = Config.DepthMode.DISABLED
                config.depthMode = Config.DepthMode.AUTOMATIC // Remettre plus tard si tout va bien

                // On s'assure que l'autofocus est activé
                config.focusMode = Config.FocusMode.AUTO

                arCoreSession?.configure(config)

                // === C'EST ICI QU'ON CONNECTE TOUT ===
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
