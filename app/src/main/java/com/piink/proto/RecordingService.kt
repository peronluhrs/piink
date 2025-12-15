package com.piink.proto

import android.app.Activity
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

    override fun onCreate() {
        super.onCreate()
        val notif = createNotification("Initialisation...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRecording()
            return START_NOT_STICKY
        }

        // On initialise resultCode à 0 (Canceled) par défaut, pas -1
        val resultCode = intent?.getIntExtra("code", 0) ?: 0
        val resultData: Intent? = intent?.getParcelableExtra("data")
        
        // RESULT_OK vaut -1. Donc on vérifie si c'est égal à -1.
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            Log.d("PiinkService", "Permission validée (Code: $resultCode). Démarrage...")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, createNotification("🔴 Ça tourne !"))
            startRecording(resultCode, resultData)
        } else {
            Log.e("PiinkService", "ERREUR: Permission invalide (Code: $resultCode)")
            // On ne stop pas immédiatement pour que vous ayez le temps de lire le toast
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
            // 1. Fichier temporaire
            val dir = getExternalFilesDir(null)
            val time = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            videoFile = File(dir, "PIINK_$time.mp4")

            // 2. Calcul Résolution (Special Sony 21:9)
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)

            // On divise par 2 et on aligne sur 16 pixels
            val width = ((metrics.widthPixels / 2) / 16) * 16
            val height = ((metrics.heightPixels / 2) / 16) * 16
            
            Log.d("PiinkService", "Config Video: $width x $height")

            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile?.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(4000000) // 4 Mbps
                setVideoFrameRate(30)
                setVideoSize(width, height)
                prepare()
            }

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(code, data)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PiinkRec", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            isRecording = true
            
            toast("🎥 REC Démarré")
            
        } catch (e: Exception) {
            Log.e("PiinkService", "CRASH START: " + e.message)
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e("PiinkService", "Stop error: " + e.message)
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
            toast("⚠️ Erreur : Vidéo vide")
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
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values)

            if (itemUri != null) {
                resolver.openOutputStream(itemUri).use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out!!)
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                
                toast("✅ SAUVÉ DANS GALERIE !")
            }
        } catch (e: Exception) {
            Log.e("PiinkService", "Erreur Galerie: " + e.message)
            toast("Erreur copie Galerie")
        }
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}
