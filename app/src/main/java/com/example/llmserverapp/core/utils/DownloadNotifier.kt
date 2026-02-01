package com.example.llmserverapp.core.utils

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.llmserverapp.ServerController

object DownloadNotifier {

    private const val CHANNEL_ID = "model_downloads"

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun updateProgress(modelName: String, progress: Float) {
        val notification = NotificationCompat.Builder(
            ServerController.appContext, CHANNEL_ID
        )
            .setContentTitle("Downloading $modelName")
            .setContentText("${(progress * 100).toInt()}%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), false)
            .build()

        NotificationManagerCompat.from(ServerController.appContext)
            .notify(modelName.hashCode(), notification)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showCompleted(modelName: String) {
        val notification = NotificationCompat.Builder(
            ServerController.appContext, CHANNEL_ID
        )
            .setContentTitle("$modelName downloaded")
            .setContentText("Ready to load")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .build()

        NotificationManagerCompat.from(ServerController.appContext)
            .notify(modelName.hashCode(), notification)
    }
}
