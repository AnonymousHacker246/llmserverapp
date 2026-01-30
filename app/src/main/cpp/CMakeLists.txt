cmake_minimum_required(VERSION 3.10.2)
project("llmserverapp")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/llama
)

add_library(
        native-lib
        SHARED
        llama_jni.cpp
)

# Prebuilt shared libs
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        llama
        ggml
        ${log-lib}
)

