cmake_minimum_required(VERSION 3.10.2)
project("llmserverapp")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------
# Include llama headers
# ---------------------------------------------------------
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/llama
        ${CMAKE_CURRENT_SOURCE_DIR}/sd        # <-- NEW: include SD headers
)

# ---------------------------------------------------------
# Native JNI library (your glue code)
# ---------------------------------------------------------
add_library(
        native-lib
        SHARED
        llama_jni.cpp
        sd_jni.cpp            # <-- NEW: SD JNI bridge
)

# ---------------------------------------------------------
# Prebuilt llama.cpp shared library
# ---------------------------------------------------------
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so
)

# ---------------------------------------------------------
# Prebuilt ggml shared library
# ---------------------------------------------------------
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so
)

# ---------------------------------------------------------
# Stable Diffusion engine (your C++ implementation)
# ---------------------------------------------------------
add_library(sd
        SHARED
        sd/sd_engine.cpp
        sd/sd_clip.cpp
        sd/sd_clip_tokenizer.cpp
        sd/sd_unet.cpp
        sd/sd_vae.cpp
        sd/sd_scheduler.cpp
        sd/sd_weight_loader.cpp
)

# Link ggml into SD engine
target_link_libraries(sd log atomic m ggml)

# ---------------------------------------------------------
# Android log library
# ---------------------------------------------------------
find_library(log-lib log)

# ---------------------------------------------------------
# Link everything into native-lib
# ---------------------------------------------------------
target_link_libraries(
        native-lib
        llama
        ggml
        sd
        ${log-lib}
)
