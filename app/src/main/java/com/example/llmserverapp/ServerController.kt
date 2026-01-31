package com.example.llmserverapp

import android.content.Context
import com.example.llmserverapp.NetworkUtils.getLocalIpAddress
import com.example.llmserverapp.core.logging.LogBuffer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private var httpServer: LocalHttpServer? = null
private var port = 0
object ServerController {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )

    lateinit var appContext: Context
    private val _isRunning = MutableStateFlow(false)
    var modelPath: String? = null
    val isRunning: StateFlow<Boolean> = _isRunning

    fun init(context: Context) {
        appContext = context.applicationContext
        modelPath = null
        // modelPath = File(appContext.filesDir, "model.gguf").absolutePath
    }
    fun startServer() {
        val path = modelPath
        if (path == null) {
            LogBuffer.info("No model loaded. Please load a model first.")
            return
        } else {

            port = 18080
            val ip = getLocalIpAddress()

            LogBuffer.info("Starting server...")

            if (httpServer == null) {
                httpServer = LocalHttpServer(port)
                try {
                    httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    LogBuffer.info("HTTP server started on " + ip + ":" + port)
                    _isRunning.value = true
                } catch (t: Throwable) {
                    LogBuffer.info("Error starting server: ${t.message}")
                    return
                }
            } else {
                LogBuffer.info("HTTP server already running")
            }
        }
    }

    fun stopServer() {
        LogBuffer.info("Stopping server...")
        _isRunning.value = false

        // Stop HTTP server
        httpServer?.stop()
        httpServer = null
        LogBuffer.info("HTTP Server Stopped")

        // Unload model AFTER server stops
        try {
            LlamaBridge.unloadModel()
            LogBuffer.info("Model unloaded ✓")
        } catch (e: Exception) {
            LogBuffer.info("Failed to unload model: ${e.message}")
        }

        // Reset modelPath so Start requires a reload
        modelPath = null
    }

    fun runBenchmark() {
        LogBuffer.info("Starting model benchmark…")

        // Run on background thread
        scope.launch(Dispatchers.Default) {
            try {
                LlamaBridge.benchmarkModel { msg ->
                    LogBuffer.info(msg)
                }
            } catch (e: Exception) {
                LogBuffer.info("Benchmark failed: ${e.message}")
            }
        }
    }
}
