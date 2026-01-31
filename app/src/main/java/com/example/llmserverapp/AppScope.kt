package com.example.llmserverapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppScope {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val default: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
