package com.example.llmserverapp.core.models

data class ModelDescriptor(
    val id: String,
    val name: String,
    val prettyName: String,
    val sizeBytes: Long,
    val url: String,
    val localPath: String,
    val downloaded: Boolean,
    val progress: Int,
    val status: ModelStatus
)

enum class ModelStatus {
    NotDownloaded,
    Downloading,
    Downloaded,
    Loading,
    Loaded,
    Error
}
