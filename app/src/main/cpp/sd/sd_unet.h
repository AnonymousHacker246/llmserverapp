#pragma once
#include <vector>
#include <string>

// ============================================================================
// Latent tensor
// ============================================================================

struct UnetLatent {
    int c = 0;   // channels
    int h = 0;   // height
    int w = 0;   // width
    std::vector<float> data;  // size = c*h*w
};

// ============================================================================
// A single convolution block (Conv2D + bias)
// ============================================================================

struct UnetBlock {
    int in_channels  = 0;
    int out_channels = 0;
    int kernel_size  = 3;

    // Weight layout: [out_c, in_c, k, k]
    std::vector<float> conv_w;

    // Bias: [out_c]
    std::vector<float> conv_b;
};

// ============================================================================
// UNet model container
// (Minimal version: down → mid → up blocks)
// ============================================================================

struct UnetModel {
    std::vector<UnetBlock> down_blocks;
    std::vector<UnetBlock> mid_blocks;
    std::vector<UnetBlock> up_blocks;
};

// ============================================================================
// Public API
// ============================================================================

// Load UNet weights from model_dir (expects unet_weights.bin)
bool sd_unet_init(const std::string& model_dir);

// Free any allocated memory (if needed)
void sd_unet_free();

// Forward pass through the UNet
UnetLatent sd_unet_forward(
        const UnetLatent& x,
        const std::vector<float>& clip_emb,
        float t
);

// Access the global UNet model
const UnetModel& sd_unet_get_model();
