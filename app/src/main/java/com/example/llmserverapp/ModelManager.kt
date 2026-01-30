package com.example.llmserverapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ModelManager {

    // ---------------------------------------------------------
    // Models to download
    // ---------------------------------------------------------
    private val MODEL_URLS = listOf(
        "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
        //"https://huggingface.co/TheBloke/CodeLlama-7B-GGUF/resolve/main/codellama-7b.Q8_0.gguf"
        // Add more models here
    )

    // ---------------------------------------------------------
    // OkHttp client
    // ---------------------------------------------------------
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------
    // Public API
    // ---------------------------------------------------------

    /** Returns true if at least one model exists locally. */
    fun modelsExist(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    /** Returns all downloaded .gguf models. */
    fun listAvailableModels(context: Context): List<File> {
        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }
            ?.toList()
            ?: emptyList()
    }

    /** Human‑friendly model name (e.g., "tinyllama" → "Tinyllama"). */
    fun prettyModelName(fileName: String): String {
        val base = fileName.substringBefore(".gguf")
        val raw = base.split("-", "_").firstOrNull() ?: base
        return raw.replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------
    // Download logic
    // ---------------------------------------------------------

    /** Downloads all models in MODEL_URLS if needed. */
    suspend fun downloadAllModels(
        context: Context,
        onProgress: (name: String, downloaded: Long, total: Long) -> Unit,
        onLog: (String) -> Unit
    ) {
        for (url in MODEL_URLS) {
            val name = url.substringAfterLast("/")
            onLog("Preparing $name…")

            downloadModelIfNeeded(
                context = context,
                url = url,
                onProgress = onProgress,
                onLog = onLog
            )
        }
    }

    /** Downloads a single model if missing or incomplete. */
    private suspend fun downloadModelIfNeeded(
        context: Context,
        url: String,
        onProgress: (name: String, downloaded: Long, total: Long) -> Unit,
        onLog: (String) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        val dir = modelDir(context)
        if (!dir.exists()) dir.mkdirs()

        val fileName = url.substringAfterLast("/")
        val finalFile = File(dir, fileName)
        val tempFile = File(dir, "$fileName.tmp")

        onLog("Checking: $fileName")

        val remoteSize = getRemoteFileSize(url)
        onLog("Remote size: ${remoteSize.prettySize()}")

        // Already downloaded?
        if (remoteSize > 0 && finalFile.exists() && finalFile.length() == remoteSize) {
            onLog("Already downloaded ✓")
            onProgress(fileName, remoteSize, remoteSize)
            return@withContext finalFile
        }

        // Delete partial file
        if (tempFile.exists()) {
            onLog("Local file incomplete (${tempFile.length().prettySize()}). Deleting…")
            tempFile.delete()
        }

        onLog("Downloading $fileName…")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            onLog("HTTP error: ${response.code}")
            return@withContext null
        }

        val body = response.body ?: run {
            onLog("Empty body")
            return@withContext null
        }

        val total = body.contentLength()
        onLog("Size: ${total.prettySize()}")

        // Stream download
        body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(fileName, downloaded, total)
                }
            }
        }

        onLog("Download complete ✓")
        tempFile.renameTo(finalFile)
        return@withContext finalFile
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private fun modelDir(context: Context): File =
        File(context.filesDir, "models")

    private suspend fun getRemoteFileSize(url: String): Long =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }

    /** Pretty formatting for file sizes. */
    fun Long.prettySize(): String {
        if (this <= 0) return "0 B"
        val gib = this / 1_073_741_824.0
        if (gib >= 1.0) return "%.2f GiB".format(gib)
        val mib = this / 1_048_576.0
        if (mib >= 1.0) return "%.2f MiB".format(mib)
        val kib = this / 1024.0
        if (kib >= 1.0) return "%.2f KiB".format(kib)
        return "$this B"
    }
}
