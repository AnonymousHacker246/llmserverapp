package com.example.llmserverapp.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object LogBuffer {

    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun append(entry: LogEntry){
        _logs.update { list ->
            (list + entry).takeLast(MAX_LOGS)
        }
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
