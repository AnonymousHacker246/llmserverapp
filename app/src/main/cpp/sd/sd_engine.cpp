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
        LOGSD("sd_generate: called before sd_init");
        return {};
    }

    LOGSD("sd_generate: begin, prompt='%s', steps=%d, guidance=%f",
          prompt.c_str(), cfg.steps, cfg.guidance);

    // 1) output size
    int out_w = (cfg.mode == SdMode::HighRes512) ? 512 : 32;
    int out_h = (cfg.mode == SdMode::HighRes512) ? 512 : 32;
    LOGSD("sd_generate: out_w=%d out_h=%d", out_w, out_h);

    int latent_c = g_latent_c;
    int latent_w = out_w / 8;
    int latent_h = out_h / 8;
    LOGSD("sd_generate: latent shape c=%d h=%d w=%d", latent_c, latent_h, latent_w);

    // 2) CLIP text embedding
    LOGSD("sd_generate: calling sd_clip_encode");
    std::vector<float> clip_emb = sd_clip_encode(prompt);
    LOGSD("sd_generate: sd_clip_encode returned, size=%zu", clip_emb.size());

    if (clip_emb.empty()) {
        LOGSD("sd_generate: ERROR - clip_emb is empty");
        return {};
    }

    // 3) scheduler
    int steps = std::max(1, cfg.steps);
    LOGSD("sd_generate: init scheduler, steps=%d", steps);
    sd_scheduler_init(steps);

    // 4) initial latent: Gaussian noise
    UnetLatent x;
    x.c = latent_c;
    x.h = latent_h;
    x.w = latent_w;
    x.data.resize((size_t)latent_c * latent_h * latent_w);
    LOGSD("sd_generate: latent data size=%zu", x.data.size());

    for (float& v : x.data) {
        v = g_normal(g_rng);
    }

    // 5) diffusion loop
    LOGSD("sd_generate: starting diffusion loop");
    for (int i = steps - 1; i >= 0; --i) {
        float abar_t = sd_scheduler_alpha_cumprod(i);
        float abar_prev = (i > 0) ? sd_scheduler_alpha_cumprod(i - 1) : 1.0f;

        LOGSD("sd_generate: step %d, calling sd_unet_forward", i);
        UnetLatent eps = sd_unet_forward(x, clip_emb, (float)i / (float)steps);
        LOGSD("sd_generate: sd_unet_forward returned, eps size=%zu", eps.data.size());

        float sqrt_abar_t = std::sqrt(abar_t);
        float sqrt_one_minus_abar_t = std::sqrt(std::max(0.0f, 1.0f - abar_t));

        std::vector<float> x0(x.data.size());
        size_t n = std::min(x.data.size(), eps.data.size());
        for (size_t k = 0; k < n; ++k) {
            x0[k] = (x.data[k] - sqrt_one_minus_abar_t * eps.data[k]) / (sqrt_abar_t + 1e-8f);
        }

        float sqrt_abar_prev = std::sqrt(abar_prev);
        float sqrt_one_minus_abar_prev = std::sqrt(std::max(0.0f, 1.0f - abar_prev));

        for (size_t k = 0; k < n; ++k) {
            x.data[k] = sqrt_abar_prev * x0[k] + sqrt_one_minus_abar_prev * eps.data[k];
        }
    }
    LOGSD("sd_generate: diffusion loop done");

    // 6) VAE decode latent → RGB image
    LOGSD("sd_generate: calling sd_vae_decode");
    SdImage img = sd_vae_decode(x.data, out_w, out_h);
    LOGSD("sd_generate: sd_vae_decode returned, w=%d h=%d rgba=%zu",
          img.width, img.height, img.rgba.size());

    return img;
}
