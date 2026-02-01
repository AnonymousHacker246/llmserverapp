    package com.example.llmserverapp

    import android.content.Context
    import com.example.llmserverapp.NetworkUtils.getLocalIpAddress
    import com.example.llmserverapp.core.logging.LogBuffer
    import com.example.llmserverapp.core.models.ModelManager
    import fi.iki.elonen.NanoHTTPD
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.launch

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
                    startUptimeTimer()
                } catch (t: Throwable) {
                    LogBuffer.error("Error starting server: ${t.message}", tag = "SERVER")
                    return
                }
            } else {
                LogBuffer.info("HTTP server already running", tag = "SERVER")
            }
        }

        // -----------------------------
    // Settings (read-only for now)
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
            if (isRunning.value){
                stopServer()
                startServer()
            }
        }


        // -----------------------------
    // Endpoint
    // -----------------------------
        val endpoint: String
            get() = "http://${getLocalIpAddress()}:$port"

        fun copyEndpoint() {
            // You already have clipboard utils — call them here
            LogBuffer.info("Endpoint copied: $endpoint", tag = "SERVER")
        }

        // -----------------------------
    // Recent Requests
    // -----------------------------
        data class RequestInfo(
            val path: String, val tokens: Int, val durationMs: Long
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
        private val _uptime = MutableStateFlow(0L)
        val uptime: StateFlow<Long> = _uptime

        fun updateMetrics(tokensPerSec: Float, durationMs: Long, tokens: Int) {
            _metrics.value = _metrics.value.copy(
                tokensPerSec = tokensPerSec,
                lastRequestMs = durationMs,
                requestCount = _metrics.value.requestCount + 1,
                totalTokens = _metrics.value.totalTokens + tokens
            )
        }

        // -----------------------------
    // Loaded model name (read-only)
    // -----------------------------
        private val _loadedModel = MutableStateFlow<String?>(null)
        val loadedModel: StateFlow<String?> = _loadedModel

        // Called by ModelManager when a model loads
        fun setLoadedModel(name: String?) {
            _loadedModel.value = name
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
            } catch (e: Exception) {
                LogBuffer.error("Failed to unload model: ${e.message}", tag = "MODEL")
            }

            // Reset modelPath so Start requires a reload
            modelPath = null
            setLoadedModel(null)
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
