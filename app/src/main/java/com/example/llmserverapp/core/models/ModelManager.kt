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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL

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

data class SdPart(
    val name: String,
    val fileName: String,
    val url: String,
    var sizeBytes: Long = 0L
)

object ModelManager {

    private val scope = AppScope.io

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models

    private var loadedModelId: String? = null
    private var didInitialRefresh = false

    // ---- SD bundle parts ----
    // Logical steps: 3 (CLIP, UNet, VAE)
    // Physical files: 4 (CLIP, UNet part-aa, UNet part-ab, VAE)
    private val sdParts = listOf(
        SdPart(
            name = "CLIP",
            fileName = "clip_weights.bin",
            url = "https://github.com/AnonymousHacker246/llmserverapp/releases/download/v1.0/clip_weights.bin",
        ),
        SdPart(
            name = "UNet Part1",
            fileName = "unet_weights.bin.part-aa",
            url = "https://github.com/AnonymousHacker246/llmserverapp/releases/download/v1.0/unet_weights.bin.part-aa",
        ),
        SdPart(
            name = "UNet Part2",
            fileName = "unet_weights.bin.part-ab",
            url = "https://github.com/AnonymousHacker246/llmserverapp/releases/download/v1.0/unet_weights.bin.part-ab",
        ),
        SdPart(
            name = "VAE",
            fileName = "vae_weights.bin",
            url = "https://github.com/AnonymousHacker246/llmserverapp/releases/download/v1.0/vae_weights.bin",
        )
    )

    fun getSdModelDir(): File {
        return File(ServerController.appContext.filesDir, "sd")
    }

    fun areSdWeightsReady(): Boolean {
        val dir = getSdModelDir()
        // After merge, we care about final files:
        // clip_weights.bin, unet_weights.bin, vae_weights.bin
        val clip = File(dir, "clip_weights.bin").exists()
        val unet = File(dir, "unet_weights.bin").exists()
        val vae = File(dir, "vae_weights.bin").exists()
        return clip && unet && vae
    }

    // ---- Pretty size ----
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

    // ---- Inference (LLaMA) ----
    suspend fun runInference(prompt: String): String {
        val loaded = _models.value.firstOrNull { it.status == ModelStatus.Loaded && it.type == ModelType.Llama }
            ?: return "No LLaMA model loaded."

        val settings = ServerController.settings.value

        return try {
            LlamaBridge.generate(
                prompt,
                settings.temperature,
                settings.maxTokens,
                settings.threads
            )
        } catch (e: Exception) {
            "Inference failed: ${e.message}"
        }
    }

    // ---- UNet merge ----
    private fun mergeUnetParts(sdDir: File) {
        val part1 = File(sdDir, "unet_weights.bin.part-aa")
        val part2 = File(sdDir, "unet_weights.bin.part-ab")
        val output = File(sdDir, "unet_weights.bin")

        if (!part1.exists() || !part2.exists()) {
            LogBuffer.error("UNet merge failed: missing part files", tag = "MODEL")
            return
        }

        FileOutputStream(output).use { out ->
            listOf(part1, part2).forEach { part ->
                part.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            }
        }

        // Cleanup
        part1.delete()
        part2.delete()

        LogBuffer.info("UNet parts merged into unet_weights.bin ✓", tag = "MODEL")
    }

    // Map physical index -> logical step index
    // 0 -> CLIP (0)
    // 1,2 -> UNet (1)
    // 3 -> VAE (2)
    private fun logicalIndexForPhysical(i: Int): Int {
        return when (i) {
            0 -> 0
            1, 2 -> 1
            3 -> 2
            else -> 2
        }
    }

    // ---- Refresh models ----
    fun refreshModels(force: Boolean = false) {
        if (!force && didInitialRefresh) return
        didInitialRefresh = true

        scope.launch {
            val ctx = ServerController.appContext

            // LLaMA models
            val llamaUrls = listOf(
                "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                "https://huggingface.co/TheBloke/CodeLlama-7B-GGUF/resolve/main/codellama-7b.Q5_K_S.gguf"
            )

            val llamaDescriptors = llamaUrls.map { url ->
                val meta = fetchModelMetadata(url)
                val file = File(ctx.filesDir, meta.fileName)

                val status =
                    if (file.exists() && file.length() == meta.sizeBytes)
                        ModelStatus.Downloaded
                    else
                        ModelStatus.NotDownloaded

                ModelDescriptor(
                    id = meta.fileName,
                    prettyName = meta.fileName,
                    fileName = meta.fileName,
                    downloadUrl = url,
                    localPath = file.absolutePath,
                    status = status,
                    sizeBytes = meta.sizeBytes,
                    type = ModelType.Llama
                )
            }

            // Fetch metadata for each SD part dynamically
            for (part in sdParts) {
                val meta = fetchModelMetadata(part.url)
                part.sizeBytes = meta.sizeBytes
            }

            // SD bundle parent
            val sdDir = getSdModelDir()
            sdDir.mkdirs()

            val sdTotalSize = sdParts.sumOf { it.sizeBytes }
            val sdAllExist = areSdWeightsReady()

            val sdStatus = when {
                sdAllExist -> ModelStatus.Downloaded
                else -> ModelStatus.NotDownloaded
            }

            val sdDescriptor = ModelDescriptor(
                id = "sd15",
                prettyName = "Stable Diffusion 1.5",
                fileName = "sd15",
                downloadUrl = "",
                localPath = sdDir.absolutePath,
                status = sdStatus,
                sizeBytes = sdTotalSize,
                type = ModelType.StableDiffusion
            )

            val merged = (llamaDescriptors + sdDescriptor).map { desc ->
                if (desc.id == loadedModelId)
                    desc.copy(status = ModelStatus.Loaded)
                else desc
            }

            _models.value = merged
        }
    }

    // ---- Download SD bundle ----
    fun downloadModel(id: String) {
        if (id == "sd15") {
            downloadSdBundle()
            return
        }

        // Normal LLaMA download
        downloadSingleFileModel(id)
    }

    private fun downloadSdBundle() {
        scope.launch {
            val sdDir = getSdModelDir()
            sdDir.mkdirs()

            _models.update { list ->
                list.map {
                    if (it.id == "sd15") it.copy(status = ModelStatus.Downloading, progress = 0f)
                    else it
                }
            }

            val totalSize = sdParts.sumOf { it.sizeBytes }
            val partProgress = LongArray(sdParts.size) { 0L }
            val logicalParts = 3 // CLIP, UNet, VAE
            var completedPhysicalParts = 0

            try {
                sdParts.forEachIndexed { index, part ->
                    val temp = File(sdDir, part.fileName + ".tmp")
                    val final = File(sdDir, part.fileName)

                    downloadSingleFile(
                        url = part.url,
                        tempFile = temp,
                        finalFile = final,
                        expectedSize = part.sizeBytes
                    ) { bytesDownloaded ->
                        partProgress[index] = bytesDownloaded
                        val totalDownloaded = partProgress.sum()

                        val progress = (totalDownloaded.toFloat() / totalSize.toFloat())
                            .coerceIn(0f, 1f)

                        val logicalIndex = logicalIndexForPhysical(index)
                        val pretty = "Stable Diffusion 1.5 (${logicalIndex + 1}/$logicalParts)"

                        _models.update { list ->
                            list.map {
                                if (it.id == "sd15")
                                    it.copy(
                                        progress = progress,
                                        prettyName = pretty
                                    )
                                else it
                            }
                        }
                    }

                    completedPhysicalParts++
                }

                // All physical parts downloaded, now merge UNet
                mergeUnetParts(sdDir)

                _models.update { list ->
                    list.map {
                        if (it.id == "sd15")
                            it.copy(
                                status = ModelStatus.Downloaded,
                                progress = 1f,
                                prettyName = "Stable Diffusion 1.5"
                            )
                        else it
                    }
                }
            } catch (e: Exception) {
                LogBuffer.error("SD bundle download failed: ${e.message}", tag = "MODEL")
                _models.update { list ->
                    list.map {
                        if (it.id == "sd15")
                            it.copy(status = ModelStatus.Failed, progress = null)
                        else it
                    }
                }
            }
        }
    }

    // ---- Single file downloader (LLaMA models) ----
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
                val ctx = ServerController.appContext
                val meta = fetchModelMetadata(url)

                val finalFile = File(model.localPath)
                finalFile.parentFile?.mkdirs()

                val tempFile = File(finalFile.parentFile, finalFile.name + ".tmp")

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

    // ---- Low-level file downloader (SD parts) ----
    private suspend fun downloadSingleFile(
        url: String,
        tempFile: File,
        finalFile: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit
    ) {
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        val connection = URL(url).openConnection().apply {
            if (existingBytes > 0) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        val input = connection.getInputStream()
        val output = FileOutputStream(tempFile, existingBytes > 0)

        val buffer = ByteArray(8 * 1024)
        var downloaded = existingBytes
        var read: Int

        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            downloaded += read
            onProgress(downloaded)
        }

        output.flush()
        output.close()
        input.close()

        if (tempFile.exists()) {
            tempFile.renameTo(finalFile)
        }
    }

    // ---- Load model ----
    fun loadModel(id: String) {
        val model = _models.value.firstOrNull { it.id == id } ?: return

        when (model.type) {
            ModelType.Llama -> {
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
            }

            ModelType.StableDiffusion -> {
                // SD engine loads all weights later via sd_init()
                loadedModelId = id
                LogBuffer.info("SD bundle marked as loaded", tag = "MODEL")
            }
        }

        _models.update { list ->
            list.map {
                when {
                    it.id == id -> it.copy(status = ModelStatus.Loaded)
                    it.status == ModelStatus.Loaded && it.type == model.type ->
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
