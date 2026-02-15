#include "sd_scheduler.h"
#include <vector>
#include <cmath>

static std::vector<float> g_alphas;
static std::vector<float> g_alphas_cumprod;

bool sd_scheduler_init(int num_steps) {
    g_alphas.resize(num_steps);
    g_alphas_cumprod.resize(num_steps);

    // Simple linear beta schedule
    float beta_start = 0.0001f;
    float beta_end   = 0.02f;

    float alpha_cum = 1.0f;
    for (int i = 0; i < num_steps; ++i) {
        float t = float(i) / float(num_steps - 1);
        float beta = beta_start + t * (beta_end - beta_start);
        float alpha = 1.0f - beta;
        g_alphas[i] = alpha;
        alpha_cum *= alpha;
        g_alphas_cumprod[i] = alpha_cum;
    }
    return true;
}

float sd_scheduler_alpha(int step) {
    return g_alphas[step];
}

float sd_scheduler_alpha_cumprod(int step) {
    return g_alphas_cumprod[step];
}
