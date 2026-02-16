package com.example.llmserverapp.core.models

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.llmserverapp.AppScope
import com.example.llmserverapp.LlamaBridge
import com.example.llmserverapp.ServerController
import com.example.llmserverapp.ServerController.settings
import com.example.llmserverapp.StableDiffusionBridge
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.services.DownloadProgressBus
import com.example.llmserverapp.core.services.DownloadWorker
import com.example.llmserverapp.core.services.ModelNotificationManager
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL

data class ModelFile(
    val name: String,
    val url: String,
    var size: Long = 0L,
    var downloaded: Boolean = false
)

data class ModelEntry(
    val id: String,
    val name: String,
    val type: String,   // "llama" or "sd"
    val version: Int,
    val files: List<ModelFile>
)

enum class ModelStatus {
    NotDownloaded,
    Downloading,
    Downloaded,
    Loaded,
    Failed
}

enum class ModelType {
    Llama,
    StableDiffusion
}

data class ModelDescriptor(
    val id: String,
    val prettyName: String,
    val fileName: String,
    val downloadUrl: String,
    val localPath: String,
    val status: ModelStatus,
    val progress: Float? = null,
    val sizeBytes: Long,
    val type: ModelType
)

object ModelManager {

    private val scope = AppScope.io

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models

    private var loadedModelId: String? = null
    private var didInitialRefresh = false

    private val modelEntries = mutableListOf<ModelEntry>()

    private val notificationJobs = mutableMapOf<String, Job>()

    private fun startNotificationLoop(modelId: String, modelName: String) {
        // Cancel old loop if exists
        notificationJobs[modelId]?.cancel()

        val job = scope.launch {
            while (isActive) {
                val descriptor = _models.value.firstOrNull { it.id == modelId }
                val pct = descriptor?.progress ?: 0f

                ModelNotificationManager.showProgress(modelId, modelName, pct)

                delay(750) // smooth, but not spammy
            }
        }

        notificationJobs[modelId] = job
    }

    private fun stopNotificationLoop(modelId: String) {
        notificationJobs[modelId]?.cancel()
        notificationJobs.remove(modelId)
    }


    suspend fun loadModelsFromRemote(url: String) {
        val json = URL(url).readText()
        val list = Gson().fromJson(json, Array<ModelEntry>::class.java)
        modelEntries.clear()
        modelEntries.addAll(list)
    }

    fun getEntryById(id: String): ModelEntry? =
        modelEntries.firstOrNull { it.id == id }

    fun getModelDir(id: String): File =
        File(ServerController.appContext.filesDir, "models/$id")

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

    suspend fun fetchRemoteFileSize(url: String): Long {
        return try {
            val conn = URL(url).openConnection()
            conn.connect()
            conn.contentLengthLong.takeIf { it > 0 } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun runInference(prompt: String): String {
        _models.value.firstOrNull { it.status == ModelStatus.Loaded && it.type == ModelType.Llama }
            ?: return "No LLaMA model loaded."

        val s = settings.value

        return try {
            LlamaBridge.generate(
                prompt,
                s.temperature,
                s.maxTokens,
                s.threads
            )
        } catch (e: Exception) {
            "Inference failed: ${e.message}"
        }
    }

    fun refreshModels(force: Boolean = false) {
        if (!force && didInitialRefresh) return
        didInitialRefresh = true

        scope.launch {
            loadModelsFromRemote("https://github.com/AnonymousHacker246/llmserverapp/releases/download/v1.0/models.json")

            val descriptors = modelEntries.map { entry ->
                val dir = getModelDir(entry.id)
                dir.mkdirs()

                val allFilesExist = entry.files.all { mf ->
                    val f = File(dir, mf.name)
                    f.exists() && f.length() > 0
                }

                val status = when {
                    loadedModelId == entry.id -> ModelStatus.Loaded
                    allFilesExist -> ModelStatus.Downloaded
                    else -> ModelStatus.NotDownloaded
                }

                entry.files.forEach { mf ->
                    if (mf.size == 0L) {
                        mf.size = fetchRemoteFileSize(mf.url)
                    }
                }

                val sizeBytes = entry.files.sumOf { mf ->
                    val f = File(dir, mf.name)
                    if (f.exists()) f.length() else mf.size
                }

                val type = when (entry.type.lowercase()) {
                    "llama" -> ModelType.Llama
                    "sd", "stablediffusion" -> ModelType.StableDiffusion
                    else -> ModelType.Llama
                }

                ModelDescriptor(
                    id = entry.id,
                    prettyName = entry.name,
                    fileName = entry.id,
                    downloadUrl = "",
                    localPath = dir.absolutePath,
                    status = status,
                    sizeBytes = sizeBytes,
                    type = type
                )
            }

            _models.value = descriptors
        }
    }

    // Called by DownloadWorker after a file finishes
    fun onFileDownloadComplete(modelId: String) {
        val entry = getEntryById(modelId) ?: return
        checkIfModelFullyDownloaded(entry)
    }

    private fun checkIfModelFullyDownloaded(entry: ModelEntry) {
        val dir = getModelDir(entry.id)
        val allFilesExist = entry.files.all { mf ->
            val f = File(dir, mf.name)
            f.exists() && f.length() > 0
        }

        if (allFilesExist) {
            stopNotificationLoop(entry.id)
            DownloadProgressBus.clear(entry.id)

            _models.update { list ->
                list.map {
                    if (it.id == entry.id)
                        it.copy(status = ModelStatus.Downloaded, progress = 1f)
                    else it
                }
            }
            ModelNotificationManager.showComplete(entry.id, entry.name)
        }
    }

    // ---- Multi-file download via WorkManager + DownloadProgressBus ----

    fun downloadModel(entry: ModelEntry) {
        val modelId = entry.id
        val modelDir = getModelDir(modelId)
        modelDir.mkdirs()

        _models.update { list ->
            list.map {
                if (it.id == modelId) it.copy(status = ModelStatus.Downloading, progress = 0f)
                else it
            }
        }

        // Observe progress for this model
        scope.launch {
            DownloadProgressBus.progress.collect { modelMap ->
                val fileMap = modelMap[modelId] ?: return@collect
                val entryLocal = getEntryById(modelId) ?: return@collect

                val totalBytes = entryLocal.files.sumOf { it.size }
                if (totalBytes <= 0) return@collect

                val downloadedBytes = entryLocal.files.sumOf { mf ->
                    val pct = fileMap[mf.name] ?: 0f
                    (mf.size * pct).toLong()
                }

                val overall = downloadedBytes.toFloat() / totalBytes.toFloat()
                updateModelProgress(modelId, overall)
            }
        }

        // Schedule each file as a WorkManager job
        entry.files.forEach { file ->
            val finalPath = File(modelDir, file.name).absolutePath
            val tempPath = "$finalPath.tmp"

            val data = workDataOf(
                "url" to file.url,
                "temp" to tempPath,
                "final" to finalPath,
                "modelId" to modelId
            )

            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(ServerController.appContext)
                .enqueue(req)
        }
        startNotificationLoop(modelId, entry.name)
    }

    private fun updateModelProgress(id: String, progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)

        _models.update { list ->
            list.map {
                if (it.id == id) it.copy(progress = clamped)
                else it
            }
        }
    }

    // ---- Legacy single-file downloader (kept for GGUF etc., unchanged) ----
    // (You can delete this if you no longer need it.)

    private fun downloadSingleFileModel(id: String) {
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
                val finalFile = File(model.localPath)
                finalFile.parentFile?.mkdirs()

                val tempFile = File(finalFile.parentFile, finalFile.name + ".tmp")

                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                val connection = URL(url).openConnection().apply {
                    if (existingBytes > 0) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val totalBytes = model.sizeBytes
                val input = connection.getInputStream()
                val output = FileOutputStream(tempFile, existingBytes > 0)

                val buffer = ByteArray(8 * 1024)
                var downloaded = existingBytes
                var read: Int

                val progressJob = scope.launch {
                    while (isActive) {
                        val progress = if (totalBytes > 0)
                            (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        else 0f

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

                if (tempFile.exists()) {
                    tempFile.renameTo(finalFile)
                }

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

    // ---- Load / unload / benchmark (unchanged) ----

    fun loadModel(id: String) {
        val descriptor = _models.value.firstOrNull { it.id == id } ?: return
        val entry = modelEntries.firstOrNull { it.id == id }

        when (descriptor.type) {
            ModelType.Llama -> {
                if (entry == null || entry.files.isEmpty()) {
                    LogBuffer.error("No ModelEntry or files for LLaMA model $id", tag = "MODEL")
                    return
                }

                val dir = getModelDir(id)
                val modelFile = File(dir, entry.files.first().name)
                if (!modelFile.exists()) {
                    LogBuffer.error("Model file missing: ${modelFile.absolutePath}", tag = "MODEL")
                    return
                }

                val result = LlamaBridge.loadModel(modelFile.absolutePath, settings.value.threads)
                if (result == 0L) {
                    LogBuffer.error("Native loadModel returned 0", tag = "MODEL")
                    return
                }

                loadedModelId = id
                ServerController.modelPath = modelFile.absolutePath
                ServerController.setLoadedModel(descriptor.prettyName)

                ModelNotificationManager.cancel(id)
            }

            ModelType.StableDiffusion -> {
                val modelDir = getModelDir(id)

                val handle = StableDiffusionBridge.sdLoadModel(modelDir.absolutePath)
                if (handle == 0L) {
                    LogBuffer.error("Stable Diffusion failed to initialize", tag = "MODEL")
                    return
                }

                loadedModelId = id
                ServerController.modelPath = modelDir.absolutePath
                ServerController.loadedModelType = ModelType.StableDiffusion
                ServerController.setLoadedModel(descriptor.prettyName)

                ModelNotificationManager.cancel(id)

                LogBuffer.info("Stable Diffusion initialized ✓", tag = "MODEL")
            }
        }

        _models.update { list ->
            list.map {
                when {
                    it.id == id -> it.copy(status = ModelStatus.Loaded)
                    it.status == ModelStatus.Loaded && it.type == descriptor.type ->
                        it.copy(status = ModelStatus.Downloaded)
                    else -> it
                }
            }
        }
    }

    fun runBenchmark() {
        val loaded = _models.value.firstOrNull {
            it.status == ModelStatus.Loaded && it.type == ModelType.Llama
        }
        if (loaded == null) {
            LogBuffer.info("Benchmark requires a loaded LLaMA model")
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
                if (it.status == ModelStatus.Loaded && it.type == ModelType.Llama)
                    it.copy(status = ModelStatus.Downloaded)
                else it
            }
        }

        LogBuffer.info("Model unloaded ✓", tag = "MODEL")
    }
}
