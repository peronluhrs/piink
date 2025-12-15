package com.piink.proto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvInfo: TextView
    private lateinit var btnShoot: ImageButton 
    private lateinit var btnRecord: ToggleButton

    private var arCoreSession: Session? = null
    private val renderer = ARRenderer(this)
    private lateinit var projectionManager: MediaProjectionManager
    
    private val CODE_REC = 101
    private val CODE_PERM = 100
    private var isRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        tvInfo = findViewById(R.id.tvDistance)
        btnShoot = findViewById(R.id.btnShoot)
        btnRecord = findViewById(R.id.btnRecord)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupGL()

        btnShoot.setOnClickListener {
            try { renderer.triggerCapture() } catch (e: Exception) {}
        }

        btnRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val intent = projectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, CODE_REC)
            } else {
                val intent = Intent(this, RecordingService::class.java)
                intent.action = "STOP"
                startService(intent)
                btnRecord.setBackgroundColor(Color.parseColor("#88000000"))
            }
        }

        checkPerms()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODE_REC) {
            if (resultCode == -1 && data != null) {
                val intent = Intent(this, RecordingService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                btnRecord.setBackgroundColor(Color.RED)
            } else {
                btnRecord.isChecked = false
                Toast.makeText(this, "Refusé", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGL() {
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun checkPerms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CODE_PERM)
        } else {
            initAR()
        }
    }

    override fun onRequestPermissionsResult(rq: Int, p: Array<String>, res: IntArray) {
        if (rq == CODE_PERM && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) initAR()
    }

    private fun initAR() {
        try {
            if (ArCoreApk.getInstance().checkAvailability(this).isSupported) {
                arCoreSession = Session(this)
                val config = Config(arCoreSession)
                config.focusMode = Config.FocusMode.AUTO
                config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                config.depthMode = Config.DepthMode.DISABLED 
                arCoreSession?.configure(config)
                renderer.currentSession = arCoreSession
                arCoreSession?.resume()
                
                // BOUCLE UI
                Thread {
                    while (isRunning) {
                        try {
                            Thread.sleep(100)
                            val m = renderer.uiMessage
                            runOnUiThread { tvInfo.text = m }
                        } catch(e:Exception){}
                    }
                }.start()
            }
        } catch (e: Exception) { }
    }

    override fun onResume() { super.onResume(); surfaceView.onResume(); arCoreSession?.resume() }
    override fun onPause() { super.onPause(); surfaceView.onPause(); arCoreSession?.pause() }
    override fun onDestroy() { super.onDestroy(); arCoreSession?.close(); isRunning = false }
}
