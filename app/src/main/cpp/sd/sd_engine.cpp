#include "sd_engine.h"
#include "sd_clip.h"
#include "sd_unet.h"
#include "sd_vae.h"
#include "sd_scheduler.h"

#include <random>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <android/log.h>

#define LOGSD(...) __android_log_print(ANDROID_LOG_INFO, "SD", __VA_ARGS__)

// -----------------------------------------------------------------------------
// Globals
// -----------------------------------------------------------------------------

static bool g_sd_ready = false;

// latent shape (SD1.5-style for 512x512)
static const int g_latent_c = 4;

// RNG
static std::mt19937 g_rng{1234};
static std::normal_distribution<float> g_normal(0.0f, 1.0f);

// -----------------------------------------------------------------------------
// Init / Free
// -----------------------------------------------------------------------------

bool sd_init(const std::string& model_dir) {

    LOGSD("module_dir = %s", model_dir.c_str());

    LOGSD("CLIP init...");
    if (!sd_clip_init(model_dir)) {
        LOGSD("CLIP init failed");
        return false;
    }

    LOGSD("UNET init...");
    if (!sd_unet_init(model_dir)) {
        LOGSD("UNET init failed");
        return false;
    }

    LOGSD("VAE init...");
    if (!sd_vae_init(model_dir)) {
        LOGSD("VAE init failed");
        return false;
    }

    LOGSD("All Modules loaded.");
    g_sd_ready = true;
    return true;
}

void sd_free() {
    sd_clip_free();
    sd_unet_free();
    sd_vae_free();
    g_sd_ready = false;
}

// -----------------------------------------------------------------------------
// Core generate
// -----------------------------------------------------------------------------

SdImage sd_generate(
        const std::string& prompt,
        const SdConfig& cfg
) {
    if (!g_sd_ready) {
        LOGSD("sd_generate called before sd_init");
        return {};
    }

    // 1) output size
    int out_w = (cfg.mode == SdMode::HighRes512) ? 512 : 32;
    int out_h = (cfg.mode == SdMode::HighRes512) ? 512 : 32;

    int latent_c = g_latent_c;
    int latent_w = out_w / 8;
    int latent_h = out_h / 8;

    // 2) CLIP text embedding
    std::vector<float> clip_emb = sd_clip_encode(prompt);

    // 3) scheduler
    int steps = std::max(1, cfg.steps);
    sd_scheduler_init(steps);

    // 4) initial latent: Gaussian noise
    UnetLatent x;
    x.c = latent_c;
    x.h = latent_h;
    x.w = latent_w;
    x.data.resize((size_t)latent_c * latent_h * latent_w);

    for (float& v : x.data) {
        v = g_normal(g_rng);
    }

    // 5) diffusion loop (DDIM-style, no guidance for now)
    for (int i = steps - 1; i >= 0; --i) {
        float abar_t = sd_scheduler_alpha_cumprod(i);
        float abar_prev = (i > 0) ? sd_scheduler_alpha_cumprod(i - 1) : 1.0f;

        // predict noise eps_t = eps(x_t, t, cond)
        UnetLatent eps = sd_unet_forward(x, clip_emb, (float)i / (float)steps);

        // x0_t = (x_t - sqrt(1 - abar_t) * eps_t) / sqrt(abar_t)
        float sqrt_abar_t = std::sqrt(abar_t);
        float sqrt_one_minus_abar_t = std::sqrt(std::max(0.0f, 1.0f - abar_t));

        std::vector<float> x0(x.data.size());
        size_t n = std::min(x.data.size(), eps.data.size());
        for (size_t k = 0; k < n; ++k) {
            x0[k] = (x.data[k] - sqrt_one_minus_abar_t * eps.data[k]) / (sqrt_abar_t + 1e-8f);
        }

        // x_{t-1} = sqrt(abar_prev) * x0 + sqrt(1 - abar_prev) * eps_t
        float sqrt_abar_prev = std::sqrt(abar_prev);
        float sqrt_one_minus_abar_prev = std::sqrt(std::max(0.0f, 1.0f - abar_prev));

        for (size_t k = 0; k < n; ++k) {
            x.data[k] = sqrt_abar_prev * x0[k] + sqrt_one_minus_abar_prev * eps.data[k];
        }
    }

    // 6) VAE decode latent → RGB image
    SdImage img = sd_vae_decode(x.data, out_w, out_h);
    return img;
}
