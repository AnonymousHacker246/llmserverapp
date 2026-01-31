package com.example.llmserverapp.core.app

import com.example.llmserverapp.ServerController
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.models.ModelManager

object LlmServerAppState {

    // Expose state flows for UI screens
    val models = ModelManager.models
    val logs = LogBuffer.logs
    val isRunning = ServerController.isRunning

    // Model operations
    fun loadModel(id: String) = ModelManager.loadModel(id)
    fun unloadModel() = ModelManager.unloadModel()
    fun runBenchmark() = ModelManager.runBenchmark()

    // Server operations
    fun startServer() = ServerController.startServer()
    fun stopServer() = ServerController.stopServer()
}