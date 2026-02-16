package com.example.llmserverapp.core.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.models.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val tempPath = inputData.getString("temp") ?: return@withContext Result.failure()
        val finalPath = inputData.getString("final") ?: return@withContext Result.failure()
        val modelId = inputData.getString("modelId") ?: return@withContext Result.failure()

        val tempFile = File(tempPath)
        val finalFile = File(finalPath)
        val fileName = finalFile.name

        try {
            // ------------------------------------------------------------
            // 1. If final file already exists → skip this file entirely
            // ------------------------------------------------------------
            if (finalFile.exists()) {
                DownloadProgressBus.update(modelId, fileName, 1f)
                ModelManager.onFileDownloadComplete(modelId)
                return@withContext Result.success()
            }

            // Ensure directory exists
            tempFile.parentFile?.mkdirs()

            // ------------------------------------------------------------
            // 2. Resume if .tmp exists
            // ------------------------------------------------------------
            var downloaded = if (tempFile.exists()) tempFile.length() else 0L

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                if (downloaded > 0) {
                    setRequestProperty("Range", "bytes=$downloaded-")
                }
                connect()
            }

            val contentLength = conn.getHeaderFieldLong("Content-Length", -1L)
            val total = if (contentLength > 0) contentLength + downloaded else -1L

            // ------------------------------------------------------------
            // 3. Download loop
            // ------------------------------------------------------------
            conn.inputStream.use { input ->
                FileOutputStream(tempFile, downloaded > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var lastUpdate = System.currentTimeMillis()

                    while (true) {
                        read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastUpdate > 200) {
                            lastUpdate = now
                            val pct = downloaded.toFloat() / total.toFloat()
                            DownloadProgressBus.update(modelId, fileName, pct)
                        }
                    }
                }
            }

            // ------------------------------------------------------------
            // 4. Finalize
            // ------------------------------------------------------------
            if (total > 0) {
                DownloadProgressBus.update(modelId, fileName, 1f)
            }

            tempFile.renameTo(finalFile)
            ModelManager.onFileDownloadComplete(modelId)

            Result.success()

        } catch (e: Exception) {
            LogBuffer.error("DownloadWorker failed: ${e.message}", tag = "MODEL")
            Result.failure()
        }
    }
}
