package com.example.llmserverapp

import org.json.JSONObject
import android.util.Log

object LlamaBridge {

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("ggml")
        System.loadLibrary("llama")
        System.loadLibrary("native-lib")
    }

    external fun loadModel(path: String): Long
    external fun unloadModel()

    // IMPORTANT: JNI returns JSON STRING, not LlamaResult
    external fun generateWithStats(prompt: String): String

    fun benchmarkModel(onLog: (String) -> Unit) {
        val prompt = "hello"

        onLog("Benchmark starting…")
        onLog("Prompt: $prompt")

        val start = System.nanoTime()
        val jsonString = generateWithStats(prompt)
        val end = System.nanoTime()
        Log.e("LLAMA_DEBUG", "RAW_JSON = $jsonString")
        val result = JSONObject(jsonString)
        val output = result.getString("text")
        val generatedTokens = result.getInt("generated")

        val elapsedMs = (end - start) / 1_000_000.0
        val speed = generatedTokens / (elapsedMs / 1000.0)

        onLog("Benchmark complete ✓")
        onLog("Generated: \"$output\"")
        onLog("Time: ${"%.2f".format(elapsedMs)} ms")
        onLog("Generated tokens: $generatedTokens")
        onLog("Speed: ${"%.2f".format(speed)} tokens/sec")
    }
}

