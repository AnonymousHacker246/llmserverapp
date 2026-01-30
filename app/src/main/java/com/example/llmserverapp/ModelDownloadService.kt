package com.example.llmserverapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llmserverapp.ModelManager.prettyModelName
import com.example.llmserverapp.ModelManager.prettySize
import kotlinx.coroutines.*

class ModelDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d("ModelDownloadService", "onCreate()")
        createNotificationChannel()
    }
    private lateinit var builder: NotificationCompat.Builder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        builder = buildBaseNotification()
        Log.d("ModelDownloadService", "onStartCommand called")
        startForeground(1, builder.build())

        scope.launch {
            Log.d("ModelDownloadService", "Coroutine started")
            ModelManager.downloadAllModels(
                context = this@ModelDownloadService,
                onProgress = { name, downloaded, total ->
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0

                    // Build clean status text
                    val shortName = prettyModelName(name)
                    val status = "Downloading $shortName  ${downloaded.prettySize()} / ${total.prettySize()}"



                    // Broadcast status to splash
                    val statusIntent = Intent("MODEL_DOWNLOAD_STATUS")
                    statusIntent.putExtra("status", status)
                    sendBroadcast(statusIntent)

                    // Broadcast percent for progress bar
                    val progressIntent = Intent("MODEL_DOWNLOAD_PROGRESS")
                    progressIntent.putExtra("progress", percent)
                    sendBroadcast(progressIntent)

                    // Update notification
                    updateNotificationThrottled(percent, status)
                },
                onLog = { msg ->
                    Log.d("ModelDownloadService", msg)
                    broadcastLog(msg)
                }

            )

            Log.d("ModelDownloadService", "All model downloads complete")
            broadcastFinished("done")

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        // IMPORTANT: stops when app is swiped away
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastProgress(progress: Int) {
        val intent = Intent("MODEL_DOWNLOAD_PROGRESS")
        intent.putExtra("progress", progress)
        sendBroadcast(intent)
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent("MODEL_DOWNLOAD_LOG")
        intent.putExtra("log", msg)
        sendBroadcast(intent)
    }


    private fun broadcastFinished(path: String) {
        val intent = Intent("MODEL_DOWNLOAD_FINISHED")
        intent.putExtra("path", path)
        sendBroadcast(intent)
    }

    private fun buildBaseNotification(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, "model_download")
            .setContentTitle("LLM Model Download")
            .setContentText("Preparing model files")
            .setSmallIcon(R.drawable.ic_download)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setWhen(0)
            .setUsesChronometer(false)
    }

    private var lastNotifyTime = 0L

    private fun updateNotificationThrottled(progress: Int, status: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 500) return
        lastNotifyTime = now

        builder.setProgress(100, progress, false)
        builder.setContentText(status)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, builder.build())
    }


    private fun updateNotification(progress: Int, text: String) {
        builder.setProgress(100, progress, false)
        builder.setContentText("Downloading... $progress%")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, builder.build())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "model_download",
            "Model Download",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
