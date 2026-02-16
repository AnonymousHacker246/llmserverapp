#pragma once
#include <vector>
#include <string>
#include "sd_engine.h"

// ============================================================================
// A single convolution block used by the VAE decoder
// ============================================================================

struct VaeConv {
    int in_channels  = 0;
    int out_channels = 0;
    int kernel_size  = 3;

    // Weight layout: [out_c, in_c, k, k]
    std::vector<float> weight;

    // Bias: [out_c]
    std::vector<float> bias;
};

// ============================================================================
// Full VAE decoder model
// (Minimal SD1.5-compatible: conv_in → up1 → up2 → conv_out)
// ============================================================================

struct VaeModel {
    VaeConv conv1;       // first conv after latent scaling
    VaeConv up1;         // upsample block 1
    VaeConv up2;         // upsample block 2
    VaeConv final_conv;  // final conv → tanh → RGB
};

// ============================================================================
// Public API
// ============================================================================

// Load VAE weights from model_dir (expects vae_weights.bin)
bool sd_vae_init(const std::string& model_dir);

// Free any allocated memory (if needed)
void sd_vae_free();

// Decode latent [4, H, W] → SdImage (RGB)
SdImage sd_vae_decode(const std::vector<float>& latent, int out_w, int out_h);
