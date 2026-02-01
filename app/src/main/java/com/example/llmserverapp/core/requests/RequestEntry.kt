package com.example.llmserverapp.core.requests

data class RequestEntry(
    val path: String,
    val tokens: Int,
    val durationMs: Long,
    val timestampMillis: Long
)
