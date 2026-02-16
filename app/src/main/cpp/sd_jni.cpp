#include <jni.h>
#include <string>
#include "sd/sd_engine.h"
#include <android/log.h>

#define LOGSDI(...) __android_log_print(ANDROID_LOG_INFO,  "SD_NATIVE", __VA_ARGS__)
#define LOGSDE(...) __android_log_print(ANDROID_LOG_ERROR, "SD_NATIVE", __VA_ARGS__)

extern "C" {

// ------------------------------------------------------------
// sdLoadModel
// ------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_example_llmserverapp_StableDiffusionBridge_sdLoadModel(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring jPath
) {
    LOGSDI("sdLoadModel: entered");

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    LOGSDI("sdLoadModel: calling sd_init with path=%s", path);

    bool ok = sd_init(std::string(path));

    LOGSDI("sdLoadModel: sd_init returned %d", ok ? 1 : 0);

    env->ReleaseStringUTFChars(jPath, path);

    LOGSDI("sdLoadModel: exiting");
    return ok ? 1L : 0L;
}

// ------------------------------------------------------------
// sdGenerate
// ------------------------------------------------------------
JNIEXPORT jbyteArray JNICALL
Java_com_example_llmserverapp_StableDiffusionBridge_sdGenerate(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring jPrompt,
        jint jSteps,
        jfloat jGuidance
) {
    LOGSDI("sdGenerate: entered");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    LOGSDI("sdGenerate: prompt='%s', steps=%d, guidance=%f",
           prompt, jSteps, jGuidance);

    SdConfig cfg;
    cfg.steps    = jSteps;
    cfg.guidance = jGuidance;

    LOGSDI("sdGenerate: calling sd_generate()");
    SdImage img = sd_generate(std::string(prompt), cfg);
    LOGSDI("sdGenerate: sd_generate() returned");

    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Validate output
    if (img.width <= 0 || img.height <= 0) {
        LOGSDE("sdGenerate: invalid image size: %dx%d", img.width, img.height);
        return nullptr;
    }
    if (img.rgba.empty()) {
        LOGSDE("sdGenerate: rgba buffer is empty");
        return nullptr;
    }

    LOGSDI("sdGenerate: image OK: %dx%d, rgba bytes=%zu",
           img.width, img.height, img.rgba.size());

    // Create Java byte[] of EXACT size
    jsize len = static_cast<jsize>(img.rgba.size());
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) {
        LOGSDE("sdGenerate: FAILED to allocate Java byte array of size %d", len);
        return nullptr;
    }

    LOGSDI("sdGenerate: copying %d bytes into Java array", len);

    env->SetByteArrayRegion(
            arr,
            0,
            len,
            reinterpret_cast<const jbyte*>(img.rgba.data())
    );

    LOGSDI("sdGenerate: exiting normally");
    return arr;
}

// ------------------------------------------------------------
// sdUnloadModel
// ------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_llmserverapp_StableDiffusionBridge_sdUnloadModel(
        JNIEnv* /*env*/,
        jobject /*thiz*/
) {
    LOGSDI("sdUnloadModel: calling sd_free()");
    sd_free();
    LOGSDI("sdUnloadModel: done");
}

} // extern "C"
