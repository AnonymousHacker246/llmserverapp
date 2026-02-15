package com.example.llmserverapp

object StableDiffusionBridge {

    init {
        // SD native lib name must match what CMake builds (e.g. "sd" or "ggml_sd")
        System.loadLibrary("sd")
        System.loadLibrary("native-lib") // already used by LlamaBridge
    }

    external fun sdLoadModel(path: String): Long

    /**
     * Returns raw image bytes (e.g. PNG or RGB buffer depending on your SD backend).
     */
    external fun sdGenerate(
        prompt: String,
        steps: Int,
        guidance: Float
    ): ByteArray?

    external fun sdUnloadModel()
}
