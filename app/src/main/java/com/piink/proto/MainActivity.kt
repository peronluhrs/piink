package com.piink.proto

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvInfo: TextView
    private lateinit var btnShoot: Button

    private var arCoreSession: Session? = null
    private val renderer = ARRenderer(this)
    private val CAMERA_PERMISSION_CODE = 100
    private var isRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        tvInfo = findViewById(R.id.tvDistance)
        btnShoot = findViewById(R.id.btnShoot)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // ACTION DU BOUTON : On dit juste au Renderer "Fais le taf"
        btnShoot.setOnClickListener {
            renderer.triggerCapture()
        }

        checkAndRequestCameraPermission()
    }

    // BOUCLE UI : Met à jour le texte 10 fois par seconde
    private fun startUiLoop() {
        Thread {
            while (isRunning) {
                try {
                    Thread.sleep(100)
                    val msg = renderer.uiMessage
                    runOnUiThread { tvInfo.text = msg }
                } catch (e: Exception) { }
            }
        }.start()
    }

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            initializeARCore()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) initializeARCore()
    }

    private fun initializeARCore() {
        try {
            if (ArCoreApk.getInstance().checkAvailability(this).isSupported) {
                arCoreSession = Session(this)
                val config = Config(arCoreSession)
                config.focusMode = Config.FocusMode.AUTO
                config.depthMode = Config.DepthMode.DISABLED
                // INSTANT PLACEMENT (Pour les surfaces difficiles)
                config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP

                arCoreSession?.configure(config)
                renderer.currentSession = arCoreSession
                arCoreSession?.resume()

                startUiLoop()
            }
        } catch (e: Exception) { }
    }

    override fun onResume() { super.onResume(); surfaceView.onResume(); arCoreSession?.resume() }
    override fun onPause() { super.onPause(); surfaceView.onPause(); arCoreSession?.pause() }
    override fun onDestroy() { super.onDestroy(); arCoreSession?.close(); isRunning = false }
}