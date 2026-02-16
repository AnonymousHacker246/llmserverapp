#pragma once
#include <string>
#include <vector>

// ============================================================================
// Image container
// ============================================================================

struct SdImage {
    int width  = 0;
    int height = 0;
    std::vector<unsigned char> rgba;   // size = width * height * 4
};

// ============================================================================
// Generation modes
// ============================================================================

enum class SdMode {
    HighRes512,   // 512x512 latent → 512x512 output
    Pixel32       // 32x32 latent → 32x32 output
};

// ============================================================================
// Generation configuration
// ============================================================================

struct SdConfig {
    SdMode mode     = SdMode::HighRes512;
    int   steps     = 20;     // diffusion steps
    float guidance  = 7.5f;   // classifier-free guidance (future use)
};

// ============================================================================
// Engine API
// ============================================================================

// Load CLIP, UNet, VAE, scheduler weights from model_dir
bool sd_init(const std::string& model_dir);

// Free all model memory
void sd_free();

// Generate an image from a text prompt
SdImage sd_generate(
        const std::string& prompt,
        const SdConfig& cfg
);
