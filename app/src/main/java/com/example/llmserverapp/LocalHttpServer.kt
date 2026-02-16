package com.example.llmserverapp

import com.example.llmserverapp.core.logging.LogBuffer
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import android.util.Base64
import com.example.llmserverapp.core.models.ModelType

class LocalHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {

        LogBuffer.info("HTTP ${session.method} ${session.uri}", tag = "HTTP")

        // Parse POST body
        var postBody: String? = null
        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            session.parseBody(body)
            postBody = body["postData"]
            if (!postBody.isNullOrBlank()) {
                LogBuffer.debug("POST body: $postBody", tag = "HTTP")
            }
        }

        return when (session.uri) {

            // -----------------------------
            // STATUS
            // -----------------------------
            "/status" -> {
                newFixedLengthResponse(
                    if (ServerController.isRunning.value) "running" else "stopped"
                )
            }

            // -----------------------------
            // LOGS
            // -----------------------------
            "/logs" -> {
                val logs = LogBuffer.logs.value.joinToString("\n") { entry ->
                    "[${entry.level}] ${entry.tag ?: ""} ${entry.message}"
                }
                newFixedLengthResponse(logs)
            }

            // -----------------------------
            // LLM: /v1/chat/completions
            // -----------------------------
            "/v1/chat/completions" -> {
                if (postBody == null) {
                    return newFixedLengthResponse("Missing POST body")
                }

                val json = JSONObject(postBody)
                val prompt = json.optString("prompt", "").trim()

                if (prompt.isEmpty()) {
                    return newFixedLengthResponse("Missing prompt")
                }

                val cfg = ServerController.settings.value
                val start = System.currentTimeMillis()

                val result: String = try {
                    LlamaBridge.generate(
                        prompt,
                        cfg.temperature,
                        cfg.maxTokens,
                        cfg.threads
                    )
                } catch (e: Exception) {
                    LogBuffer.error("LLM generation failed: ${e.message}", "MODEL")
                    return newFixedLengthResponse("Error: ${e.message}")
                }

                val durationMs = System.currentTimeMillis() - start
                val tokens = result.length
                val tps = if (durationMs > 0) tokens / (durationMs / 1000f) else 0f

                ServerController.updateMetrics(tps, durationMs, tokens)
                ServerController.addRequest(
                    ServerController.RequestInfo(
                        path = session.uri,
                        tokens = tokens,
                        durationMs = durationMs
                    )
                )

                val responseJson = JSONObject().apply {
                    put("text", result)
                    put("generated", tokens)
                    put("duration_ms", durationMs)
                    put("tokens_per_sec", tps)
                }

                return newFixedLengthResponse(responseJson.toString())
            }

            // -----------------------------
            // SD: /v1/images/generations
            // -----------------------------
            "/v1/images/generations" -> {
                if (postBody == null) {
                    return newFixedLengthResponse("Missing POST body")
                }

                val json = JSONObject(postBody)
                val prompt = json.optString("prompt", "").trim()
                val steps = json.optInt("steps", 20)
                val guidance = json.optDouble("guidance", 4.0).toFloat()

                if (prompt.isEmpty()) {
                    return newFixedLengthResponse("Missing prompt")
                }

                val start = System.currentTimeMillis()

                val bytes: ByteArray = try {
                    StableDiffusionBridge.sdGenerate(prompt, steps, guidance)
                        ?: return newFixedLengthResponse("SD model not loaded")
                } catch (e: Exception) {
                    LogBuffer.error("SD generation failed: ${e.message}", "MODEL")
                    return newFixedLengthResponse("Error: ${e.message}")
                }

                val durationMs = System.currentTimeMillis() - start

                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val responseJson = JSONObject().apply {
                    put("image", base64)
                    put("duration_ms", durationMs)
                }

                return newFixedLengthResponse(responseJson.toString())
            }

// -----------------------------
// SD TEST: /sd_test
// -----------------------------
            "/sd_test" -> {
                if (ServerController.loadedModelType != ModelType.StableDiffusion) {
                    return newFixedLengthResponse("Stable Diffusion is not loaded")
                }

                val prompt = "black silhouette of a character, strong outline, no interior detail"
                val steps = 20
                val guidance = 7.5f

                val start = System.currentTimeMillis()

                val rgba = try {
                    StableDiffusionBridge.sdGenerate(prompt, steps, guidance)
                        ?: return newFixedLengthResponse("SD generation failed (null bytes)")
                } catch (e: Exception) {
                    LogBuffer.error("SD test generation failed: ${e.message}", "MODEL")
                    return newFixedLengthResponse("Error: ${e.message}")
                }

                val durationMs = System.currentTimeMillis() - start

                // Convert raw RGBA → PNG
                val png = ImageUtils.rgbaToPng(rgba, 512, 512)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    png.inputStream(),
                    png.size.toLong()
                )

                response.addHeader("X-Generation-Time", durationMs.toString())
                return response
            }


            // -----------------------------
            // UNKNOWN
            // -----------------------------
            else -> newFixedLengthResponse("unknown endpoint")
        }
    }
}
