package com.example.llmserverapp.core.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground immediately
        // startForeground(NOTIF_ID, buildNotification("Preparing download…", 0))

        val url = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val tempPath = intent.getStringExtra("temp") ?: return START_NOT_STICKY
        val finalPath = intent.getStringExtra("final") ?: return START_NOT_STICKY

        val tempFile = File(tempPath)
        val finalFile = File(finalPath)


        scope.launch {
            downloadFile(url, tempFile, finalFile)
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading model")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)

        if (progress != null) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int?) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "app:download"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun downloadFile(urlStr: String, temp: File, final: File) {
        temp.parentFile?.mkdirs()

        var downloaded = if (temp.exists()) temp.length() else 0L

        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            if (downloaded > 0) {
                setRequestProperty("Range", "bytes=$downloaded-")
            }
            connect()
        }

        val total = (conn.getHeaderFieldLong("Content-Length", -1L) +
                downloaded).takeIf { it > 0 } ?: -1L

        conn.inputStream.use { input ->
            temp.outputStream().use { output ->
                if (downloaded > 0) {
                    output.channel.position(downloaded)
                }

                val buffer = ByteArray(1024 * 64)
                var read: Int
                var lastUpdate = System.currentTimeMillis()

                while (true) {
                    read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500) {
                        lastUpdate = now
                        if (total > 0) {
                            val progress = (downloaded * 100 / total).toInt()
                            updateNotification("Downloading… $progress%", progress)
                        } else {
                            updateNotification("Downloading…", null)
                        }
                    }
                }
            }
        }

        temp.renameTo(final)
        updateNotification("Download complete", 100)
    }
}
