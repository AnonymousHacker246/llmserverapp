package com.example.llmserverapp

import com.example.llmserverapp.core.logging.LogBuffer
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class LocalHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {

        LogBuffer.info("HTTP ${session.method} ${session.uri}", tag = "HTTP")

        if (session.parameters.isNotEmpty()) {
            LogBuffer.debug("Query params: ${session.parameters}", tag = "HTTP")
        }

        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"]
            if (!postData.isNullOrBlank()) {
                LogBuffer.debug("POST body: $postData", tag = "HTTP")
            }
        }

        return when (session.uri) {

            "/status" -> {
                newFixedLengthResponse(
                    if (ServerController.isRunning.value) "running" else "stopped"
                )
            }

            "/logs" -> {
                val logs = LogBuffer.logs.value.joinToString("\n") { entry ->
                    "[${entry.level}] ${entry.tag ?: ""} ${entry.message}"
                }
                newFixedLengthResponse(logs)
            }

            "/diagnose",
            "/generate" -> {

                val start = System.currentTimeMillis()

                val prompt = session.parameters["prompt"]
                    ?.firstOrNull()
                    ?.trim()
                    ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: return newFixedLengthResponse("Missing or empty prompt")

                val maxTokens = session.parameters["max_tokens"]
                    ?.firstOrNull()
                    ?.toIntOrNull()
                    ?: 64

                LogBuffer.info("Generating response (maxTokens=$maxTokens)", tag = "MODEL")

                // These must be declared BEFORE the try block
                var result: String = ""
                var tokens: Int = 0

                try {
                    val cfg = ServerController.settings.value
                    result = LlamaBridge.generate(
                        prompt,
                        cfg.temperature,
                        cfg.maxTokens,
                        cfg.threads
                    )
                    tokens = result.length
                } catch (e: Exception) {
                    LogBuffer.error("Generation failed: ${e.message}", "MODEL")
                    return newFixedLengthResponse("Error: ${e.message}")
                }

                val durationMs = System.currentTimeMillis() - start
                val tokensPerSec =
                    if (durationMs > 0) tokens.toFloat() / (durationMs / 1000f) else 0f

                // Update metrics
                ServerController.updateMetrics(tokensPerSec, durationMs, tokens)

                // Add request to recent list
                ServerController.addRequest(
                    ServerController.RequestInfo(
                        path = session.uri,
                        tokens = tokens,
                        durationMs = durationMs
                    )
                )

                LogBuffer.info("Completed ${session.uri} in ${durationMs}ms", tag = "HTTP")

                val threads = LlamaBridge.getThreadCount()

                val responseJson = JSONObject().apply {
                    put("text", result)
                    put("generated", tokens)
                    put("threads", threads)
                    put("duration_ms", durationMs)
                    put("tokens_per_sec", tokensPerSec)
                }

                return newFixedLengthResponse(responseJson.toString())

            }

            else -> newFixedLengthResponse("unknown endpoint")
        }
    }
}
