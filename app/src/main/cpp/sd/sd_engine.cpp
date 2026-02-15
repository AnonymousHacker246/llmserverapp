#include "sd_engine.h"
#include "sd_clip.h"
#include "sd_unet.h"
#include "sd_vae.h"
#include "sd_scheduler.h"
#include <random>

// ---- add these ----
static bool g_sd_ready = false;

// latent shape (SD1.5-style)
static int g_latent_c = 4;
static int g_latent_w = 64;
static int g_latent_h = 64;

static std::mt19937 g_rng{1234};
static std::normal_distribution<float> g_normal(0.0f, 1.0f);
// -------------------

bool sd_init(const std::string& model_dir) {
    // CLIP loads its own weights from model_dir
    if (!sd_clip_init(model_dir)) return false;

    // UNet loads unet_weights.bin from model_dir
    if (!sd_unet_init(model_dir)) return false;

    // VAE loads vae weights (or uses fake ones for now)
    if (!sd_vae_init()) return false;

    // Scheduler
    sd_scheduler_init(20);

    g_sd_ready = true;
    return true;
}



void sd_free() {
    // TODO: free all ggml contexts / weights
    g_sd_ready = false;
}

SdImage sd_generate(
        const std::string& prompt,
        const SdConfig& cfg
) {
    int out_w = (cfg.mode == SdMode::HighRes512) ? 512 : 32;
    int out_h = (cfg.mode == SdMode::HighRes512) ? 512 : 32;

    int latent_c = 4;
    int latent_w = out_w / 8;
    int latent_h = out_h / 8;

    // 1) CLIP
    std::vector<float> clip_emb = sd_clip_encode(prompt);

    // 2) Initial latent (noise)
    UnetLatent x;
    x.c = latent_c;
    x.h = latent_h;
    x.w = latent_w;
    x.data.resize(latent_c * latent_h * latent_w);
    for (size_t i = 0; i < x.data.size(); ++i) {
        x.data[i] = 0.0f; // later: random normal
    }

    int steps = cfg.steps;
    for (int i = 0; i < steps; ++i) {
        float t = float(i) / float(steps - 1);

        UnetLatent eps = sd_unet_forward(x, clip_emb, t);

        // very fake update: x = x - eps * 0.1
        for (size_t k = 0; k < x.data.size(); ++k) {
            x.data[k] -= eps.data[k] * 0.1f;
        }
    }

    // 3) VAE decode
    SdImage img = sd_vae_decode(x.data, out_w, out_h);
    return img;
}

