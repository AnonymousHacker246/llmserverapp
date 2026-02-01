package com.example.llmserverapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.llmserverapp.core.AppVisibility
import com.example.llmserverapp.core.logging.LogBuffer


class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createDownloadChannel()
        ServerController.init(this)
        AppVisibility.init()
    }

    private fun createDownloadChannel() {
        val channel = NotificationChannel(
            "model_downloads",
            "Model Downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        LogBuffer.info("Download channel created")
    }
}
