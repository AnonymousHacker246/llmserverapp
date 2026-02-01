package com.example.llmserverapp.core.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

class DownloadService : Service() {

    companion object {
        @Volatile
        var instance: DownloadService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // No-op now
    fun updateProgress(progress: Float) {
        // intentionally empty
    }

    fun stopServiceSafely() {
        stopSelf()
    }
}
