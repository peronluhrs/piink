package com.piink.proto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Config

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private var arCoreSession: Session? = null

    private val CAMERA_PERMISSION_CODE = 100
    private val TAG = "PiinkProto_ARCore"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logText)
        logTextView.text = "PIINK TEKNOLOGY - Initialisation ARCore..."

        // Vérifie la permission et lance ARCore si c'est bon
        checkAndRequestCameraPermission()
    }

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            // Permission déjà accordée
            initializeARCore()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                initializeARCore()
            } else {
                logTextView.text = "ERREUR: La permission CAMERA est requise pour ARCore."
            }
        }
    }

    private fun initializeARCore() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            
            if (availability.isTransient) {
                logTextView.text = "Statut ARCore: Temporairement indisponible. Réessaie..."
                return
            }
            
            if (availability.isSupported) {
                // Création de la session
                arCoreSession = Session(this)
                
                // Configuration de la profondeur
                val config = Config(arCoreSession)
                config.depthMode = Config.DepthMode.AUTOMATIC
                arCoreSession?.configure(config)

                logTextView.text = "✅ SUCCÈS : Session ARCore initialisée.\n"
                logTextView.append("-> Depth Mode configuré sur AUTOMATIC.\n")
                logTextView.append("-> Le capteur ToF est maintenant piloté par Google ARCore.\n\n")
                logTextView.append("L'accès 3D est DÉBLOQUÉ.")

            } else {
                // Version simplifiée pour éviter les erreurs de noms d'enum
                logTextView.text = "❌ ÉCHEC ARCore: Non supporté (${availability})"
            }

        } catch (e: Exception) {
            logTextView.text = "❌ ÉCHEC ARCore (Exception):\n${e.message}"
            Log.e(TAG, "ARCore initialization failed", e)
        }
    }
    
    // Ajout important pour la gestion du cycle de vie ARCore
    override fun onResume() {
        super.onResume()
        try {
            arCoreSession?.resume()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur onResume", e)
        }
    }

    override fun onPause() {
        super.onPause()
        arCoreSession?.pause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        arCoreSession?.close()
    }
}
