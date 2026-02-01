package com.example.llmserverapp.core.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DownloadService : Service() {

    companion object {
        @Volatile
        var instance: DownloadService? = null
            private set
    }
    private lateinit var builder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        instance = this

        builder = NotificationCompat.Builder(this, "model_downloads")
            .setContentTitle("Downloading model")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        startForeground(1, builder.build())
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateProgress(progress: Float) {
        val percent = (progress * 100).toInt()

        builder
            .setContentText("$percent%")
            .setProgress(100, percent, false)

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, builder.build())
    }


    fun stopServiceSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -----------------------------
    // Notification Builders
    // -----------------------------

    private fun buildIndeterminateNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "model_downloads")
            .setContentTitle("Downloading model")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true) // indeterminate spinner
            .build()
    }

    private fun buildProgressNotification(progress: Int, max: Int): Notification {
        return NotificationCompat.Builder(this, "model_downloads")
            .setContentTitle("Downloading model")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, false)
            .build()
    }
}
