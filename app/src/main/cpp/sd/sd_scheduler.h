#pragma once
#include <vector>

// -----------------------------------------------------------------------------
// Initialize scheduler for N steps.
// Precomputes alpha_t, alpha_cumprod_t, sigma_t.
// -----------------------------------------------------------------------------
bool sd_scheduler_init(int num_steps);

// -----------------------------------------------------------------------------
// Per-step accessors
// -----------------------------------------------------------------------------
float sd_scheduler_alpha(int step);          // α_t
float sd_scheduler_alpha_cumprod(int step);  // ᾱ_t (cumulative product)
float sd_scheduler_sigma(int step);          // σ_t (noise level)

// -----------------------------------------------------------------------------
// Optional: expose the full schedule if needed
// -----------------------------------------------------------------------------
struct SdSchedule {
    std::vector<float> alphas;         // α_t
    std::vector<float> alphas_cumprod; // ᾱ_t
    std::vector<float> sigmas;         // σ_t
};

// Build a schedule object (optional helper)
SdSchedule sd_make_schedule(int steps);
