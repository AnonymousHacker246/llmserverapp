package com.example.llmserverapp.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * modelId -> (fileName -> pct)
 */
object DownloadProgressBus {

    private val _progress =
        MutableStateFlow<Map<String, Map<String, Float>>>(emptyMap())
    val progress: StateFlow<Map<String, Map<String, Float>>> = _progress

    fun update(modelId: String, fileName: String, pct: Float) {
        _progress.value = _progress.value.toMutableMap().apply {
            val fileMap = (this[modelId] ?: emptyMap()).toMutableMap()
            fileMap[fileName] = pct.coerceIn(0f, 1f)
            this[modelId] = fileMap
        }
    }

    fun clear(modelId: String) {
        _progress.value = _progress.value.toMutableMap().apply {
            remove(modelId)
        }
    }
}
