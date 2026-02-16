#pragma once
#include <vector>
#include <string>
#include "sd_engine.h"

// -----------------------------------------------------------------------------
// Basic conv
// -----------------------------------------------------------------------------
struct VaeConv {
    int in_channels  = 0;
    int out_channels = 0;
    int kernel_size  = 0;
    std::vector<float> weight;
    std::vector<float> bias;
};

// -----------------------------------------------------------------------------
// GroupNorm (SD uses GroupNorm(32, C))
// -----------------------------------------------------------------------------
struct VaeNorm {
    int num_channels = 0;
    int num_groups   = 32;
    float eps        = 1e-5f;
    std::vector<float> weight;
    std::vector<float> bias;
};

// -----------------------------------------------------------------------------
// Residual block: norm1 -> conv1 -> norm2 -> conv2 + optional shortcut
// -----------------------------------------------------------------------------
struct VaeResBlock {
    VaeNorm norm1;
    VaeNorm norm2;
    VaeConv conv1;
    VaeConv conv2;
    bool has_shortcut = false;
    VaeConv nin_shortcut; // used if in/out channels differ
};

// -----------------------------------------------------------------------------
// One up block: 3 resblocks + optional upsample conv
// -----------------------------------------------------------------------------
struct VaeUpBlock {
    VaeResBlock block0;
    VaeResBlock block1;
    VaeResBlock block2;
    bool has_upsample = false;
    VaeConv upsample_conv;
};

// -----------------------------------------------------------------------------
// Full VAE decoder model
// -----------------------------------------------------------------------------
struct VaeModel {
    VaeConv conv_in;

    // mid block: block_1, block_2 (attention skipped)
    VaeResBlock mid_block1;
    VaeResBlock mid_block2;

    // up blocks 0..3
    VaeUpBlock up0;
    VaeUpBlock up1;
    VaeUpBlock up2;
    VaeUpBlock up3;

    VaeNorm norm_out;
    VaeConv conv_out;
};

extern VaeModel g_vae;

// Public API
bool sd_vae_init(const std::string& model_dir);
void sd_vae_free();
SdImage sd_vae_decode(const std::vector<float>& latent, int out_w, int out_h);
