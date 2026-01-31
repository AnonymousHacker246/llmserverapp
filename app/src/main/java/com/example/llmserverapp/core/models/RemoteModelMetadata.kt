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
    val rawName = disposition
        ?.substringAfter("filename=", missingDelimiterValue = "")
        ?.trim()
        ?.ifEmpty { null }
        ?: url.path.substringAfterLast('/')

    val cleanName = rawName
        .trim()
        .removePrefix("\"")
        .removeSuffix("\"")
        .removeSuffix(";")
        .removeSuffix("\";")
        .replace("\"", "")
        .replace(";", "")


    conn.disconnect()

    return RemoteModelMetadata(
        fileName = cleanName,
        sizeBytes = size
    )
}
