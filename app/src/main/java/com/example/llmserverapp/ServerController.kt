package com.example.llmserverapp

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import com.example.llmserverapp.ModelManager.prettySize
import com.example.llmserverapp.NetworkUtils.getLocalIpAddress
import kotlinx.coroutines.Dispatchers
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

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    fun init(context: Context) {
        appContext = context.applicationContext
        modelPath = null
        // modelPath = File(appContext.filesDir, "model.gguf").absolutePath
    }
    fun startServer() {
        val path = modelPath
        if (path == null) {
            appendLog("No model loaded. Please load a model first.")
            return
        } else {

            port = 18080
            val ip = getLocalIpAddress()

            appendLog("Starting server...")

            if (httpServer == null) {
                httpServer = LocalHttpServer(port)
                try {
                    httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    appendLog("HTTP server started on " + ip + ":" + port)
                    _isRunning.value = true
                } catch (t: Throwable) {
                    appendLog("Error starting server: ${t.message}")
                    return
                }
            } else {
                appendLog("HTTP server already running")
            }
        }
    }

    fun stopServer() {
        appendLog("Stopping server...")
        _isRunning.value = false

        // Stop HTTP server
        httpServer?.stop()
        httpServer = null
        appendLog("HTTP Server Stopped")

        // Unload model AFTER server stops
        try {
            LlamaBridge.unloadModel()
            appendLog("Model unloaded ✓")
        } catch (e: Exception) {
            appendLog("Failed to unload model: ${e.message}")
        }

        // Reset modelPath so Start requires a reload
        modelPath = null
    }

    fun runBenchmark() {
        appendLog("Starting model benchmark…")

        // Run on background thread
        scope.launch(Dispatchers.Default) {
            try {
                LlamaBridge.benchmarkModel { msg ->
                    appendLog(msg)
                }
            } catch (e: Exception) {
                appendLog("Benchmark failed: ${e.message}")
            }
        }
    }


    fun appendLog(message: String) {
        _logs.value = _logs.value + message
    }
}
