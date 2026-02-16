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
            tempFile.parentFile?.mkdirs()

            var downloaded = if (tempFile.exists()) tempFile.length() else 0L

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                if (downloaded > 0) {
                    setRequestProperty("Range", "bytes=$downloaded-")
                }
                connect()
            }

            val contentLength = conn.getHeaderFieldLong("Content-Length", -1L)
            val total = if (contentLength > 0) contentLength + downloaded else -1L

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

            // Final 100% update if we know total
            if (total > 0) {
                DownloadProgressBus.update(modelId, fileName, 1f)
            }

            tempFile.renameTo(finalFile)

            // Let ModelManager check if the whole model is now complete
            ModelManager.onFileDownloadComplete(modelId)

            Result.success()
        } catch (e: Exception) {
            LogBuffer.error("DownloadWorker failed: ${e.message}", tag = "MODEL")
            Result.failure()
        }
    }
}
