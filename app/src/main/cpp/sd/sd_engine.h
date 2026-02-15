#pragma once
#include <string>
#include <vector>

struct SdImage {
    int width;
    int height;
    std::vector<unsigned char> rgba; // 4 * w * h
};

enum class SdMode {
    HighRes512,
    Pixel32
};

struct sdImage {
    int width;
    int height;
    std::vector<unsigned char> rgba;
};

struct SdConfig {
    SdMode mode = SdMode::HighRes512;
    int steps  = 20;
    float guidance = 7.5f;
};

bool sd_init(const std::string& model_dir);
void sd_free();

SdImage sd_generate(
        const std::string& prompt,
        const SdConfig& cfg
);
