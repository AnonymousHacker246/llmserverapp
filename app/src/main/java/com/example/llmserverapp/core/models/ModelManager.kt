package com.example.llmserverapp.core.models

import android.content.Context
import com.example.llmserverapp.ServerController
import com.example.llmserverapp.core.logging.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

object ModelManager {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    private var loadedModelId: String? = null
    val models: StateFlow<List<ModelDescriptor>> = _models

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        val base = context.filesDir.absolutePath
        val urls = listOf(
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
        )

        val descriptors = urls.map { url ->
            val meta = fetchModelMetadata(url)
            val localPath = "$base/${meta.fileName}"
            val exists = File(localPath).exists()

            ModelDescriptor(
                id = meta.fileName,
                name = meta.fileName,
                prettyName = meta.fileName.removeSuffix(".gguf"),
                sizeBytes = meta.sizeBytes,
                url = url,
                localPath = localPath,
                downloaded = exists,
                status = when {
                    loadedModelId == meta.fileName -> ModelStatus.Loaded
                    exists -> ModelStatus.Downloaded
                    else -> ModelStatus.NotDownloaded
                },
                progress = if (exists) 100 else 0
            )
        }

        // Merge with existing list to preserve runtime state
        _models.update { old ->
            descriptors.map { new ->
                val prev = old.firstOrNull { it.id == new.id }
                if (prev != null) {
                    new.copy(
                        status = prev.status,
                        progress = prev.progress,
                        downloaded = prev.downloaded
                    )
                } else new
            }
        }
    }

    suspend fun downloadModel(id: String) {
        val model = _models.value.first { it.id == id }

        _models.update { list ->
            list.map {
                if (it.id == id) it.copy(status = ModelStatus.Downloading, progress = 0)
                else it
            }
        }

        try {
            withContext(Dispatchers.IO) {
                val url = URL(model.url)
                val conn = url.openConnection()
                val total = conn.contentLengthLong
                val input = conn.getInputStream()
                val output = File(model.localPath).outputStream()

                var downloaded = 0L
                val buffer = ByteArray(8192)

                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    val percent = ((downloaded * 100) / total).toInt()

                    _models.update { list ->
                        list.map {
                            if (it.id == id) it.copy(progress = percent)
                            else it
                        }
                    }
                }

                output.close()
                input.close()
            }

            _models.update { list ->
                list.map {
                    if (it.id == id)
                        it.copy(
                            status = ModelStatus.Downloaded,
                            downloaded = true,
                            progress = 100
                        )
                    else it
                }
            }

            LogBuffer.info("Model ${model.prettyName} downloaded", tag = "MODEL")

        } catch (e: Exception) {
            _models.update { list ->
                list.map {
                    if (it.id == id) it.copy(status = ModelStatus.Error)
                    else it
                }
            }
            LogBuffer.error(
                "Failed to download ${model.prettyName}: ${e.message}",
                tag = "MODEL"
            )
        }
    }

    fun loadModel(id: String) {
        val model = _models.value.first { it.id == id }

        loadedModelId = id

        _models.update { list ->
            list.map {
                when (it.id) {
                    id -> it.copy(status = ModelStatus.Loaded)
                    else -> it.copy(status = ModelStatus.Downloaded)
                }
            }
        }

        try {
            ServerController.modelPath = model.localPath
            LogBuffer.info("Loaded model ${model.prettyName}", tag = "MODEL")
        } catch (e: Exception) {
            LogBuffer.error("Failed to load model: ${e.message}", tag = "MODEL")
        }
    }

    fun unloadModel() {
        loadedModelId = null

        _models.update { list ->
            list.map {
                if (it.status == ModelStatus.Loaded)
                    it.copy(status = ModelStatus.Downloaded)
                else it
            }
        }
    }


    fun download(id: String) {
        scope.launch {
            downloadModel(id)
        }
    }

    fun load(id: String) {
        loadModel(id)
    }
}
