package com.piink.proto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoFile: File? = null
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    // CORRECTION : On appelle startForeground directement dans onCreate
    // Sans essayer de "surcharger" (override) la méthode système
    override fun onCreate() {
        super.onCreate()
        val notif = createNotification("Initialisation...")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) {
            Log.e("REC", "Erreur startForeground: " + e.message)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRecording()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("code", 0) ?: 0
        val resultData: Intent? = intent?.getParcelableExtra("data")
        
        // RESULT_OK vaut -1
        if (resultCode == -1 && resultData != null) {
            // Petit délai pour la stabilité
            Handler(Looper.getMainLooper()).postDelayed({
                startRecording(resultCode, resultData)
            }, 500)
        } else {
            Log.e("REC", "Permission manquante (Code: $resultCode)")
        }

        return START_STICKY
    }

    private fun createNotification(text: String): Notification {
        val channelId = "RecChannel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Enregistrement", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Piink AR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startRecording(code: Int, data: Intent) {
        try {
            val dir = getExternalFilesDir(null)
            val time = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            videoFile = File(dir, "PIINK_$time.mp4")

            // Calcul Résolution pour Sony 21:9
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)

            // Division par 2 et alignement sur 16
            val width = ((metrics.widthPixels / 2) / 16) * 16
            val height = ((metrics.heightPixels / 2) / 16) * 16
            val dpi = metrics.densityDpi

            Log.d("REC", "Resolution: $width x $height")

            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile?.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(5000000)
                setVideoFrameRate(30)
                setVideoSize(width, height)
                prepare()
            }

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(code, data)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PiinkRec", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            isRecording = true
            
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, createNotification("🔴 REC: $width x $height"))
            
            toast("🎥 REC Démarré !")
            
        } catch (e: Exception) {
            Log.e("REC", "CRASH START: " + e.message)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e("REC", "Erreur Stop: " + e.message)
        }
        
        try {
            virtualDisplay?.release()
            mediaProjection?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        
        isRecording = false
        stopForeground(true)
        stopSelf()

        if (videoFile != null && videoFile!!.exists() && videoFile!!.length() > 0) {
            saveToGallery(videoFile!!)
        } else {
            toast("⚠️ Fichier vide")
        }
    }

    private fun saveToGallery(sourceFile: File) {
        try {
            val values = ContentValues().apply {
                val timestamp = System.currentTimeMillis()
                put(MediaStore.Video.Media.DISPLAY_NAME, "Piink_$timestamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                resolver.openOutputStream(uri).use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out!!) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                toast("✅ SAUVÉ DANS GALERIE !")
            }
        } catch (e: Exception) {
            Log.e("REC", "Erreur copie: " + e.message)
        }
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}
