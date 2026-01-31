package com.example.llmserverapp.core.models

import java.net.HttpURLConnection
import java.net.URL

data class RemoteModelMetadata(
    val fileName: String,
    val sizeBytes: Long
)

suspend fun fetchModelMetadata(urlString: String): RemoteModelMetadata {
    val url = URL(urlString)
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "HEAD"
    conn.connectTimeout = 5000
    conn.readTimeout = 5000

    val size = conn.contentLengthLong

    val disposition = conn.getHeaderField("Content-Disposition")
    val fileName = when {
        disposition != null && disposition.contains("filename=") ->
            disposition.substringAfter("filename=").trim('"')
        else ->
            url.path.substringAfterLast("/")
    }

    conn.disconnect()

    return RemoteModelMetadata(
        fileName = fileName,
        sizeBytes = size
    )
}
