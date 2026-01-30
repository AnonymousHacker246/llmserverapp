package com.example.llmserverapp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.URLDecoder


class LocalHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {

            "/status" -> {
                newFixedLengthResponse(
                    if (ServerController.isRunning.value) "running" else "stopped"
                )
            }

            "/logs" -> {
                val logs = ServerController.logs.value.joinToString("\n")
                newFixedLengthResponse(logs)
            }

            "/diagnose" -> {
                // Placeholder — LLM logic will go here
                val prompt = "Diagnose request from HTTP client"
                Log.i("MLC_KT", "Before native call")
                val result = try {
                    LlamaBridge.generateWithStats(prompt)
                } catch (e: Exception) {
                    "Error running diagnose: ${e.message}"
                }
                Log.i("MLC_KT", "After native call: $result")
                return NanoHTTPD.newFixedLengthResponse(result)
            }

            "/generate" -> {
                Log.i("LLM_SERVER", "Raw query: ${session.queryParameterString}")
                Log.i("LLM_SERVER", "Params: ${session.parameters}")

                val prompt = session.parameters["prompt"]
                    ?.firstOrNull()
                    ?.trim()
                    ?: return newFixedLengthResponse("Missing prompt")
                val maxTokens = session.parameters["max_tokens"]
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
