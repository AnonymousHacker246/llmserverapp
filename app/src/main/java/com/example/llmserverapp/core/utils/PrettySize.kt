package com.example.llmserverapp.core.utils

fun Long.prettySize(): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024

    return when {
        this >= gb -> String.format("%.2f GB", this.toDouble() / gb)
        this >= mb -> String.format("%.2f MB", this.toDouble() / mb)
        this >= kb -> String.format("%.2f KB", this.toDouble() / kb)
        else -> "$this B"
    }
}
