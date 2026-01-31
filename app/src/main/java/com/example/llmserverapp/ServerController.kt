package com.example.llmserverapp

import android.content.Context
import com.example.llmserverapp.NetworkUtils.getLocalIpAddress
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.models.ModelManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private var httpServer: LocalHttpServer? = null
private var port = 0

object ServerController {

    // Use AppScope.default instead of a private scope
    private val scope = AppScope.default

    lateinit var appContext: Context
    private val _isRunning = MutableStateFlow(false)
    var modelPath: String? = null
    val isRunning: StateFlow<Boolean> = _isRunning

    fun init(context: Context) {
        appContext = context.applicationContext
        modelPath = null
    }

    fun startServer() {
        val path = modelPath
        if (path == null) {
            LogBuffer.info("No model loaded. Please load a model first.", tag = "SERVER")
            return
        }

        port = 18080
        val ip = getLocalIpAddress()

        LogBuffer.info("Starting server…", tag = "SERVER")

        if (httpServer == null) {
            httpServer = LocalHttpServer(port)
            try {
                httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                LogBuffer.info("HTTP server started on $ip:$port", tag = "SERVER")
                _isRunning.value = true
            } catch (t: Throwable) {
                LogBuffer.error("Error starting server: ${t.message}", tag = "SERVER")
                return
            }
        } else {
            LogBuffer.info("HTTP server already running", tag = "SERVER")
        }
    }

    fun stopServer() {
        LogBuffer.info("Stopping server…", tag = "SERVER")
        _isRunning.value = false

        // Stop HTTP server
        httpServer?.stop()
        httpServer = null
        LogBuffer.info("HTTP Server Stopped", tag = "SERVER")

        // Unload model AFTER server stops
        try {
            LlamaBridge.unloadModel()
            ModelManager.unloadModel()
            LogBuffer.info("Model unloaded ✓", tag = "MODEL")
        } catch (e: Exception) {
            LogBuffer.error("Failed to unload model: ${e.message}", tag = "MODEL")
        }

        // Reset modelPath so Start requires a reload
        modelPath = null
    }
}
