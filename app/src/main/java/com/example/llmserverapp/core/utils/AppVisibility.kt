package com.example.llmserverapp.core

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.llmserverapp.core.logging.LogBuffer

object AppVisibility : DefaultLifecycleObserver {

    @Volatile
    var isForeground: Boolean = true
        private set

    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        update(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        update(false)
    }

    private fun update(value: Boolean) {
        if (isForeground != value) {
            isForeground = value
            LogBuffer.info("App Visibility = ${if (value) "Foreground" else "Background"}")
        }
    }
}
