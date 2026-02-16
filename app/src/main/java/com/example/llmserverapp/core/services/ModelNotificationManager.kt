package com.example.llmserverapp.core.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.llmserverapp.ServerController

object ModelNotificationManager {

    private const val CHANNEL_ID = "model_downloads"

    private val ctx: Context
        get() = ServerController.appContext

    private val mgr: NotificationManager
        get() = ctx.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    fun showProgress(modelId: String, modelName: String, progress: Float) {
        val pct = (progress * 100).toInt()

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setContentText("$pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build()

        mgr.notify(modelId.hashCode(), notif)
    }

    fun showComplete(modelId: String, modelName: String) {
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("$modelName downloaded")
            .setContentText("Ready to load")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .build()

        mgr.notify(modelId.hashCode(), notif)
    }

    fun cancel(modelId: String) {
        mgr.cancel(modelId.hashCode())
    }
}
