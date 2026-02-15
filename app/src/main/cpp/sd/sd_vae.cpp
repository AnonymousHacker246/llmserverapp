#include "sd_vae.h"
#include <algorithm>

static VaeModel g_vae;

bool sd_vae_init() {
    // later: load weights
    return true;
}

void sd_vae_free() {
}

SdImage sd_vae_decode(const std::vector<float>& latent, int out_w, int out_h) {
    SdImage img;
    img.width = out_w;
    img.height = out_h;
    img.rgba.resize(out_w * out_h * 4);

    // latent is [4, H, W] → just map to grayscale for now
    int latent_c = 4;
    int latent_h = out_h / 8;
    int latent_w = out_w / 8;

    // naive upsample: just tile
    for (int y = 0; y < out_h; ++y) {
        for (int x = 0; x < out_w; ++x) {
            int ly = y / 8;
            int lx = x / 8;
            int idx_lat = 0 * (latent_h * latent_w) + ly * latent_w + lx;
            float v = latent[idx_lat];

            unsigned char g = (unsigned char)std::clamp(v * 127.0f + 127.0f, 0.0f, 255.0f);

            int idx = (y * out_w + x) * 4;
            img.rgba[idx + 0] = g;
            img.rgba[idx + 1] = g;
            img.rgba[idx + 2] = g;
            img.rgba[idx + 3] = 255;
        }
    }

    return img;
}
