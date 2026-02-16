#include "sd_scheduler.h"
#include <vector>
#include <cmath>
#include <iostream>

static std::vector<float> g_alphas;
static std::vector<float> g_alphas_cumprod;
static std::vector<float> g_sigmas;

// ----------------------------------------------------------------------------
// DDIM scheduler (Stable Diffusion 1.5 compatible)
// ----------------------------------------------------------------------------

bool sd_scheduler_init(int num_steps) {
    g_alphas.resize(num_steps);
    g_alphas_cumprod.resize(num_steps);
    g_sigmas.resize(num_steps);

    // DDIM uses a cosine schedule for betas
    // beta_t = 1 - (cos(pi * t / (2T))^2 / cos(pi * (t-1) / (2T))^2)
    // alpha_t = 1 - beta_t
    // alpha_cumprod_t = product(alpha_i)

    float prev = 1.0f;
    for (int i = 0; i < num_steps; ++i) {
        float t = float(i) / float(num_steps - 1);

        float ft = std::cos((t + 0.008f) / 1.008f * M_PI * 0.5f);
        float fprev = (i == 0)
                      ? 1.0f
                      : std::cos(((float(i - 1) / float(num_steps - 1)) + 0.008f) / 1.008f * M_PI * 0.5f);

        float alpha_cum = (ft * ft) / (fprev * fprev);
        float beta = std::clamp(1.0f - alpha_cum, 0.0001f, 0.999f);
        float alpha = 1.0f - beta;

        g_alphas[i] = alpha;
        g_alphas_cumprod[i] = prev * alpha;
        prev = g_alphas_cumprod[i];

        // sigma_t = sqrt((1 - alpha_cumprod_t) / alpha_cumprod_t)
        g_sigmas[i] = std::sqrt((1.0f - g_alphas_cumprod[i]) / g_alphas_cumprod[i]);
    }

    return true;
}

float sd_scheduler_alpha(int step) {
    return g_alphas[step];
}

float sd_scheduler_alpha_cumprod(int step) {
    return g_alphas_cumprod[step];
}

float sd_scheduler_sigma(int step) {
    return g_sigmas[step];
}
