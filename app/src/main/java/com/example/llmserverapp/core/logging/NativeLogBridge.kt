package com.example.llmserverapp.core.logging

import androidx.annotation.Keep

@Keep
object NativeLogBridge {

    @JvmStatic
    @Keep
    fun onNativeLog(level: Int, tag: String?, message: String?) {
        val logLevel = when (level) {
            0 -> LogLevel.DEBUG
            1 -> LogLevel.INFO
            2 -> LogLevel.WARN
            3 -> LogLevel.ERROR
            else -> LogLevel.INFO
        }

        LogBuffer.append(
            LogEntry(
                message = message ?: "<null>",
                level = logLevel,
                tag = tag ?: "NATIVE"
            )
        )
    }
}
