package com.example.llmserverapp.core

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.llmserverapp.core.logging.LogBuffer

object AppVisibility : DefaultLifecycleObserver {

    var isForeground = true
        private set

    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        LogBuffer.info("App Visibility = Foreground")
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        LogBuffer.info("App Visibility = Background")
    }
}
