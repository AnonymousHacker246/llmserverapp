#include <jni.h>
#include <string>
#include "sd/sd_engine.h"

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
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    bool ok = sd_init(std::string(path));
    env->ReleaseStringUTFChars(jPath, path);

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
    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);

    SdConfig cfg;
    cfg.steps    = jSteps;
    cfg.guidance = jGuidance;

    SdImage img = sd_generate(std::string(prompt), cfg);

    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Validate output
    if (img.width <= 0 || img.height <= 0 || img.rgba.empty()) {
        return nullptr;
    }

    jsize len = static_cast<jsize>(img.rgba.size());
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) {
        return nullptr;
    }

    env->SetByteArrayRegion(
            arr,
            0,
            len,
            reinterpret_cast<const jbyte*>(img.rgba.data())
    );

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
sd_free();
}

} // extern "C"
