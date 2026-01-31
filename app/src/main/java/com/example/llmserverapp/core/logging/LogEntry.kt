package com.example.llmserverapp.core.logging

data class LogEntry(
    val message: String,
    val level: LogLevel,
    val tag: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)
