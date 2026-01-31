package com.example.llmserverapp

import org.json.JSONObject

object LlamaBridge {

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("ggml")
        System.loadLibrary("llama")
        System.loadLibrary("native-lib")
    }

    external fun loadModel(path: String): Long
    external fun unloadModel()

    fun benchmarkModel(modelName: String, onLog: (String) -> Unit) {
        adaptiveBenchmark(
            passes = 5,
            prompt = "hello",
            modelName = modelName,
            onLog = onLog
        )
    }

    // IMPORTANT: JNI returns JSON STRING, not LlamaResult
    external fun generateWithStats(prompt: String): String

    data class BenchmarkConfig(
        var threads: Int = 4,
        var genTokens: Int = 32,
        var temp: Float = 0.7f,
        var topP: Float = 0.9f,
        var topK: Int = 40
    )

    fun formatConfigLine(config: BenchmarkConfig): String {
        return "Config: " +
                "threads=${config.threads} | " +
                "genTokens=${config.genTokens} | " +
                "topK=${config.topK} | " +
                "topP=${config.topP} | " +
                "temp=${config.temp}"
    }

    fun adaptiveBenchmark(
        passes: Int = 5,
        prompt: String = "hello",
        modelName: String,
        onLog: (String) -> Unit
    ) {
        val config = BenchmarkConfig()
        var bestSpeed = 0.0
        var bestConfig = config.copy()

        onLog("=== Adaptive Benchmark Starting for $modelName ===")

        repeat(passes) { pass ->
            onLog("---- Pass ${pass + 1} ----")
            onLog(formatConfigLine(config))

            val start = System.nanoTime()

            val jsonString = try {
                generateWithStats(prompt)
            } catch (e: Throwable) {
                onLog("CRASH: JNI threw exception: ${e.message}")
                adjustConfigAfterCrash(config, onLog)
                return@repeat
            }

            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

            val result = try {
                JSONObject(jsonString)
            } catch (e: Throwable) {
                onLog("CRASH: Invalid JSON returned")
                adjustConfigAfterCrash(config, onLog)
                return@repeat
            }

            val tokens = result.optInt("generated", -1)
            if (tokens <= 0) {
                onLog("CRASH: No tokens generated")
                adjustConfigAfterCrash(config, onLog)
                return@repeat
            }

            val tps = tokens / (elapsedMs / 1000.0)
            onLog("Tokens/sec: ${"%.2f".format(tps)}")

            if (tps > bestSpeed) {
                bestSpeed = tps
                bestConfig = config.copy()
            }

            // Optional: adjust config upward if stable
            config.threads = (config.threads + 1).coerceAtMost(8)
        }

        onLog("=== Benchmark Complete ===")
        onLog("Best speed: ${"%.2f".format(bestSpeed)} tokens/sec")
        onLog("Best config: " + formatConfigLine(bestConfig).removePrefix("Config: "))
    }

    fun adjustConfigAfterCrash(config: BenchmarkConfig, onLog: (String) -> Unit) {
        onLog("Adjusting config after crash...")

        // Reduce threads first
        if (config.threads > 1) {
            config.threads--
            onLog("Reduced threads to ${config.threads}")
            return
        }

        // Reduce generation length
        if (config.genTokens > 8) {
            config.genTokens /= 2
            onLog("Reduced genTokens to ${config.genTokens}")
            return
        }

        // Reduce sampling aggressiveness
        config.topK = (config.topK / 2).coerceAtLeast(10)
        config.topP = (config.topP - 0.1f).coerceAtLeast(0.5f)
        config.temp = (config.temp - 0.1f).coerceAtLeast(0.5f)

        onLog("Reduced sampling params: temp=${config.temp}, topP=${config.topP}, topK=${config.topK}")

    }
}
