package com.example.llmserverapp.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LogBuffer {

    private const val MAX_LINES = 5000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun append(entry: LogEntry) {
        val current = _logs.value
        val next = if (current.size >= MAX_LINES) {
            current.drop(current.size - MAX_LINES + 1) + entry
        } else {
            current + entry
        }
        _logs.value = next
    }

    fun info(msg: String, tag: String? = null) =
        append(LogEntry(msg, LogLevel.INFO, tag))

    fun warn(msg: String, tag: String? = null) =
        append(LogEntry(msg, LogLevel.WARN, tag))

    fun error(msg: String, tag: String? = null) =
        append(LogEntry(msg, LogLevel.ERROR, tag))

    fun debug(msg: String, tag: String? = null) =
        append(LogEntry(msg, LogLevel.DEBUG, tag))
}
