package com.example.llmserverapp

import android.util.Log
import com.example.llmserverapp.core.logging.LogBuffer
import fi.iki.elonen.NanoHTTPD


class LocalHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {

        LogBuffer.info(
            msg = "HTTP ${session.method} ${session.uri}",
            tag = "HTTP"
        )
        if (session.parameters.isNotEmpty()){
            LogBuffer.debug(
                msg = "Query params: ${session.parameters}",
                tag = "HTTP"
            )
        }

        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"]
            if (!postData.isNullOrBlank()) {
                LogBuffer.debug(
                    msg = "POST body: $postData",
                    tag = "HTTP"
                )
            }
        }

        return when (session.uri) {

            "/status" -> {
                newFixedLengthResponse(
                    if (ServerController.isRunning.value) "running" else "stopped"
                )
            }

            "/logs" -> {
                val logs = LogBuffer.logs.value
                    .joinToString("\n") { entry ->
                        "[${entry.level}] ${entry.tag ?: ""} ${entry.message}"
                    }
                newFixedLengthResponse(logs)
            }


            "/diagnose" -> {
                System.currentTimeMillis()
                LogBuffer.info("HTTP ${session.method} /generate", tag = "HTTP")

                session.queryParameterString?.let {
                    LogBuffer.debug("Raw query: $it", tag = "HTTP")
                }

                if (session.parameters.isNotEmpty()){
                    LogBuffer.debug("Params: ${session.parameters}", tag = "HTTP")
                }

                val prompt = session.parameters["prompt"]
                    ?.firstOrNull()
                    ?.trim()
                    ?: run{
                        LogBuffer.warn("Missing prompt parameter", tag = "HTTP")
                        return newFixedLengthResponse("Missing prompt")
                    }
                val maxTokens = session.parameters["max_tokens"]
                    ?.firstOrNull()
                    ?.toIntOrNull()
                    ?: 64

                LogBuffer.info("Generating response (maxTokens=$maxTokens)", tag = "MODEL")

                val result = try {
                    LlamaBridge.generateWithStats(prompt)
                } catch (e: Exception) {
                    LogBuffer.error("Generation failed: ${e.message}", "MODEL")
                    return newFixedLengthResponse("Error: ${e.message}")
                }

                val duration = System.currentTimeMillis()
                LogBuffer.info("Completed /generate in ${duration}ms", tag = "HTTP")
                return newFixedLengthResponse(result)
            }

            "/generate" -> {
                Log.i("LLM_SERVER", "Raw query: ${session.queryParameterString}")
                Log.i("LLM_SERVER", "Params: ${session.parameters}")

                val prompt = session.parameters["prompt"]
                    ?.firstOrNull()
                    ?.trim()
                    ?: return newFixedLengthResponse("Missing prompt")
                session.parameters["max_tokens"]
                    ?.firstOrNull()
                    ?.toIntOrNull()
                    ?: 64
                val result = LlamaBridge.generateWithStats(prompt)
                return newFixedLengthResponse(result)
            }


            else -> newFixedLengthResponse("unknown endpoint")
        }
    }
}
