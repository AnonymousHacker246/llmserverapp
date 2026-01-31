package com.example.llmserverapp.core.models

import com.example.llmserverapp.LlamaBridge
import com.example.llmserverapp.ServerController
import com.example.llmserverapp.core.logging.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

enum class ModelStatus {
    NotDownloaded,
    Downloading,
    Downloaded,
    Loaded
}

data class ModelDescriptor(
    val id: String,
    val prettyName: String,
    val fileName: String,
    val downloadUrl: String,
    val localPath: String,
    val status: ModelStatus,
    val progress: Float? = null
)

object ModelManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models

    private var loadedModelId: String? = null

    // ------------------------------------------------------------
    // Refresh model list
    // ------------------------------------------------------------
    fun refreshModels() {
        scope.launch {
            val urls = listOf(
                "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                "https://huggingface.co/TheBloke/CodeLlama-7B-GGUF/resolve/main/codellama-7b.Q5_K_S.gguf"
            )

            val descriptors = urls.map { url ->
                val meta = fetchModelMetadata(url)
                val file = File(ServerController.appContext.filesDir, meta.fileName)

                ModelDescriptor(
                    id = meta.fileName,
                    prettyName = meta.fileName.removeSuffix(".gguf"),
                    fileName = meta.fileName,
                    downloadUrl = url,
                    localPath = file.absolutePath,
                    status = if (file.exists() && file.length() > 0)
                        ModelStatus.Downloaded
                    else
                        ModelStatus.NotDownloaded
                )
            }

            _models.value = descriptors
        }
    }

    // ------------------------------------------------------------
    // Download model (with progress)
    // ------------------------------------------------------------
    fun downloadModel(id: String) {
        scope.launch {

            // Set to Downloading
            _models.update { list ->
                list.map {
                    if (it.id == id) it.copy(status = ModelStatus.Downloading, progress = 0f)
                    else it
                }
            }

            val model = _models.value.first { it.id == id }
            val url = model.downloadUrl

            try {
                val connection = URL(url).openConnection()
                val total = connection.contentLengthLong
                val input = connection.getInputStream()

                val file = File(ServerController.appContext.filesDir, model.fileName)
                val output = file.outputStream()

                val buffer = ByteArray(8 * 1024)
                var downloaded = 0L
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read

                    val progress = downloaded.toFloat() / total.toFloat()

                    _models.update { list ->
                        list.map {
                            if (it.id == id) it.copy(progress = progress)
                            else it
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()

                // Success
                _models.update { list ->
                    list.map {
                        if (it.id == id)
                            it.copy(status = ModelStatus.Downloaded, progress = null)
                        else it
                    }
                }

            } catch (e: Exception) {
                LogBuffer.error("Download failed: ${e.message}", tag = "MODEL")

                // Remove partial file
                val file = File(ServerController.appContext.filesDir, model.fileName)
                if (file.exists()) file.delete()

                // Reset status
                _models.update { list ->
                    list.map {
                        if (it.id == id)
                            it.copy(status = ModelStatus.NotDownloaded, progress = null)
                        else it
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Load Model
    // ------------------------------------------------------------
    fun loadModel(id: String) {
        val model = _models.value.firstOrNull { it.id == id } ?: return

        if (!File(model.localPath).exists()) {
            LogBuffer.error("Model file missing: ${model.localPath}", tag = "MODEL")
            return
        }

        val result = LlamaBridge.loadModel(model.localPath)
        if (result == 0L) {
            LogBuffer.error("Native loadModel returned 0", tag = "MODEL")
            return
        }

        loadedModelId = id
        ServerController.modelPath = model.localPath

        _models.update { list ->
            list.map {
                when {
                    it.id == id -> it.copy(status = ModelStatus.Loaded)
                    it.status == ModelStatus.Loaded -> it.copy(status = ModelStatus.Downloaded)
                    else -> it
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Benchmark
    // ------------------------------------------------------------
    fun runBenchmark() {
        val loaded = _models.value.firstOrNull { it.status == ModelStatus.Loaded }
        if (loaded == null) {
            LogBuffer.info("Benchmark requires a loaded model", tag = "BENCHMARK")
            return
        }

        LogBuffer.info("Starting benchmark for ${loaded.prettyName}", tag = "BENCHMARK")

        scope.launch(Dispatchers.Default) {
            try {
                LlamaBridge.benchmarkModel { msg ->
                    LogBuffer.info(msg, tag = "BENCHMARK")
                }
            } catch (e: Exception) {
                LogBuffer.error("Benchmark failed: ${e.message}", tag = "BENCHMARK")
            }
        }
    }

    // ------------------------------------------------------------
    // Unload Model
    // ------------------------------------------------------------
    fun unloadModel() {
        LogBuffer.info("Unloading model…", tag = "MODEL")

        try {
            LlamaBridge.unloadModel()
        } catch (e: Exception) {
            LogBuffer.error("Native unloadModel failed: ${e.message}", tag = "MODEL")
        }

        loadedModelId = null
        ServerController.modelPath = null

        _models.update { list ->
            list.map {
                if (it.status == ModelStatus.Loaded)
                    it.copy(status = ModelStatus.Downloaded)
                else it
            }
        }

        LogBuffer.info("Model unloaded ✓", tag = "MODEL")
    }
}
