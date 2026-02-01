package com.example.llmserverapp.core.models

import com.example.llmserverapp.AppScope
import com.example.llmserverapp.LlamaBridge
import com.example.llmserverapp.ServerController
import com.example.llmserverapp.ServerController.settings
import com.example.llmserverapp.core.logging.LogBuffer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.isActive
import java.net.URL

enum class ModelStatus {
    NotDownloaded,
    Downloading,
    Downloaded,
    Loaded,
    Failed
}

data class ModelDescriptor(
    val id: String,
    val prettyName: String,
    val fileName: String,
    val downloadUrl: String,
    val localPath: String,
    val status: ModelStatus,
    val progress: Float? = null,
    val sizeBytes: Long
)

object ModelManager {

    private val scope = AppScope.io

    private fun prettyBaseName(fileName: String): String {
        return fileName
            .removeSuffix(".gguf")
            .substringBefore('-')
            .lowercase()
    }

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models

    private var loadedModelId: String? = null

    private var didInitialRefresh = false

    fun prettySize(bytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            bytes >= gb -> String.format("%.1f GB", bytes.toDouble() / gb)
            bytes >= mb -> String.format("%.1f MB", bytes.toDouble() / mb)
            bytes >= kb -> String.format("%.1f KB", bytes.toDouble() / kb)
            else -> "$bytes B"
        }
    }

    fun refreshModels(force: Boolean = false) {
        if (!force && didInitialRefresh) return
        didInitialRefresh = true
        scope.launch {
            val urls = listOf(
                "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                "https://huggingface.co/TheBloke/CodeLlama-7B-GGUF/resolve/main/codellama-7b.Q5_K_S.gguf"
            )

            val descriptors = urls.map { url ->
                val meta = fetchModelMetadata(url)
                val file = File(ServerController.appContext.filesDir, meta.fileName)

                val baseStatus =
                    if (file.exists() && file.length() == meta.sizeBytes)
                        ModelStatus.Downloaded
                    else
                        ModelStatus.NotDownloaded

                ModelDescriptor(
                    id = meta.fileName,
                    prettyName = prettyBaseName(meta.fileName),
                    fileName = meta.fileName,
                    downloadUrl = url,
                    localPath = file.absolutePath,
                    status = baseStatus,
                    sizeBytes = meta.sizeBytes
                )
            }

            val merged = descriptors.map { desc ->
                if (desc.id == loadedModelId)
                    desc.copy(status = ModelStatus.Loaded)
                else
                    desc
            }

            _models.value = merged
        }
    }

    fun downloadModel(id: String) {
        scope.launch {

            _models.update { list ->
                list.map {
                    if (it.id == id) it.copy(status = ModelStatus.Downloading, progress = 0f)
                    else it
                }
            }

            val model = _models.value.first { it.id == id }
            val url = model.downloadUrl

            try {
                val ctx = ServerController.appContext
                val meta = fetchModelMetadata(url)
                val finalFile = File(ctx.filesDir, model.fileName)
                val tempFile = File(ctx.filesDir, model.fileName + ".tmp")

                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                val connection = URL(url).openConnection().apply {
                    if (existingBytes > 0) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val totalBytes = meta.sizeBytes
                val input = connection.getInputStream()
                val output = FileOutputStream(tempFile, existingBytes > 0)

                val buffer = ByteArray(8 * 1024)
                var downloaded = existingBytes
                var read: Int

                val progressJob = scope.launch {
                    while (isActive) {
                        val progress = (downloaded.toFloat() / totalBytes.toFloat())
                            .coerceIn(0f, 1f)

                        _models.update { list ->
                            list.map {
                                if (it.id == id) it.copy(progress = progress)
                                else it
                            }
                        }

                        delay(100)
                    }
                }

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                }

                progressJob.cancelAndJoin()

                output.flush()
                output.close()
                input.close()

                // Rename .tmp → final
                if (tempFile.exists()) {
                    tempFile.renameTo(finalFile)
                }

                // Final UI update
                _models.update { list ->
                    list.map {
                        if (it.id == id)
                            it.copy(status = ModelStatus.Downloaded, progress = 1f)
                        else it
                    }
                }

            } catch (e: Exception) {
                LogBuffer.error("Download failed: ${e.message}", tag = "MODEL")

                _models.update { list ->
                    list.map {
                        if (it.id == id) it.copy(status = ModelStatus.Failed, progress = null)
                        else it
                    }
                }
            }
        }
    }


    fun loadModel(id: String) {
        val model = _models.value.firstOrNull { it.id == id } ?: return
        val file = File(model.localPath)

        if (!file.exists()) {
            LogBuffer.error("Model file missing: ${model.localPath}", tag = "MODEL")
            return
        }

        val result = LlamaBridge.loadModel(model.localPath, settings.value.threads)
        if (result == 0L) {
            LogBuffer.error("Native loadModel returned 0", tag = "MODEL")
            return
        }

        loadedModelId = id
        ServerController.modelPath = model.localPath

        ServerController.setLoadedModel(model.prettyName)

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

    fun runBenchmark() {
        val loaded = _models.value.firstOrNull { it.status == ModelStatus.Loaded }
        if (loaded == null) {
            LogBuffer.info("Benchmark requires a loaded model")
            return
        }

        AppScope.default.launch {
            try {
                LlamaBridge.benchmarkModel(loaded.prettyName) { msg ->
                    LogBuffer.info(msg)
                }
            } catch (e: Exception) {
                LogBuffer.error("Benchmark failed: ${e.message}")
            }
        }
    }

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
