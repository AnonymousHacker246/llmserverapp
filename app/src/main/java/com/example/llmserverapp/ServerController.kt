package com.example.llmserverapp

import android.R
import android.R.attr.value
import android.content.Context
import com.example.llmserverapp.NetworkUtils.getLocalIpAddress
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.models.ModelManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Array.set
import kotlin.coroutines.EmptyCoroutineContext.get

private var httpServer: LocalHttpServer? = null

object ServerController {

    private val scope = AppScope.default

    lateinit var appContext: Context

    // -----------------------------
    // Server State
    // -----------------------------
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _uptime = MutableStateFlow(0L)
    val uptime: StateFlow<Long> = _uptime

    // -----------------------------
    // Model Paths (LLM + SD)
    // -----------------------------
    var llmModelPath: String? = null
    var sdModelPath: String? = null

    var modelPath: String?
        get() = llmModelPath
        set(value) { llmModelPath = value }

    fun setLoadedModel(name: String?){
        setLoadedLLM(name)
    }

    // -----------------------------
    // Settings
    // -----------------------------
    data class ServerSettings(
        val port: Int = 18080,
        val maxTokens: Int = 256,
        val temperature: Float = 0.7f,
        val threads: Int = 4,
        val contextLength: Int = 2048
    )

    private val _settings = MutableStateFlow(ServerSettings())
    val settings: StateFlow<ServerSettings> = _settings

    fun updatePort(newPort: Int) {
        _settings.value = _settings.value.copy(port = newPort)
    }

    fun updateMaxTokens(newMax: Int) {
        _settings.value = _settings.value.copy(maxTokens = newMax)
    }

    fun updateTemperature(newTemp: Float) {
        _settings.value = _settings.value.copy(temperature = newTemp)
    }

    fun updateThreads(newThreads: Int) {
        _settings.value = _settings.value.copy(threads = newThreads)
        if (isRunning.value) {
            stopServer()
            startServer()
        }
    }

    // -----------------------------
    // Loaded Model Names
    // -----------------------------
    private val _loadedLLM = MutableStateFlow<String?>(null)
    val loadedLLM: StateFlow<String?> = _loadedLLM

    private val _loadedSD = MutableStateFlow<String?>(null)
    val loadedSD: StateFlow<String?> = _loadedSD

    fun setLoadedLLM(name: String?) {
        _loadedLLM.value = name
    }

    fun setLoadedSD(name: String?) {
        _loadedSD.value = name
    }

    // -----------------------------
    // Endpoint
    // -----------------------------
    val endpoint: String
        get() = "http://${getLocalIpAddress()}:${_settings.value.port}"

    fun copyEndpoint() {
        LogBuffer.info("Endpoint copied: $endpoint", tag = "SERVER")
    }

    // -----------------------------
    // Recent Requests
    // -----------------------------
    data class RequestInfo(
        val path: String,
        val tokens: Int,
        val durationMs: Long
    )

    private val _recentRequests = MutableStateFlow<List<RequestInfo>>(emptyList())
    val recentRequests: StateFlow<List<RequestInfo>> = _recentRequests

    fun addRequest(info: RequestInfo) {
        _recentRequests.value = (listOf(info) + _recentRequests.value).take(20)
    }

    // -----------------------------
    // Metrics
    // -----------------------------
    data class ServerMetrics(
        val tokensPerSec: Float = 0f,
        val lastRequestMs: Long = 0,
        val requestCount: Int = 0,
        val totalTokens: Long = 0L
    )

    private val _metrics = MutableStateFlow(ServerMetrics())
    val metrics: StateFlow<ServerMetrics> = _metrics

    fun updateMetrics(tokensPerSec: Float, durationMs: Long, tokens: Int) {
        _metrics.value = _metrics.value.copy(
            tokensPerSec = tokensPerSec,
            lastRequestMs = durationMs,
            requestCount = _metrics.value.requestCount + 1,
            totalTokens = _metrics.value.totalTokens + tokens
        )
    }

    // -----------------------------
    // Init
    // -----------------------------
    fun init(context: Context) {
        appContext = context.applicationContext
        llmModelPath = null
        sdModelPath = null
    }

    // -----------------------------
    // Server Lifecycle
    // -----------------------------
    fun startServer() {
        val port = _settings.value.port

        if (llmModelPath == null && sdModelPath == null) {
            LogBuffer.info("No models loaded. Load LLM or SD first.", tag = "SERVER")
            return
        }

        if (httpServer != null) {
            LogBuffer.info("HTTP server already running", tag = "SERVER")
            return
        }

        try {
            httpServer = LocalHttpServer(port)
            httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

            LogBuffer.info("HTTP server started on $endpoint", tag = "SERVER")
            _isRunning.value = true
            startUptimeTimer()

        } catch (t: Throwable) {
            LogBuffer.error("Error starting server: ${t.message}", tag = "SERVER")
        }
    }

    fun stopServer() {
        LogBuffer.info("Stopping server…", tag = "SERVER")
        _isRunning.value = false

        httpServer?.stop()
        httpServer = null

        LogBuffer.info("HTTP Server Stopped", tag = "SERVER")

        try {
            LlamaBridge.unloadModel()
            StableDiffusionBridge.sdUnloadModel()
            ModelManager.unloadModel()
        } catch (e: Exception) {
            LogBuffer.error("Failed to unload models: ${e.message}", tag = "MODEL")
        }

        llmModelPath = null
        sdModelPath = null

        setLoadedLLM(null)
        setLoadedSD(null)
        _uptime.value = 0
    }

    private fun startUptimeTimer() {
        scope.launch {
            while (isRunning.value) {
                kotlinx.coroutines.delay(1000)
                _uptime.value += 1
            }
        }
    }
}
