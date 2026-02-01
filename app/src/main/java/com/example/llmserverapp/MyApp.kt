package com.example.llmserverapp

import android.app.Application
import com.example.llmserverapp.core.AppVisibility
import com.example.llmserverapp.core.logging.LogBuffer

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerController.init(this)
        AppVisibility.init()
        LogBuffer.info("MyApp initialized")
    }
}
