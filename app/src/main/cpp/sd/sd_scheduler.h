#pragma once
#include <vector>

bool sd_scheduler_init(int num_steps);
float sd_scheduler_alpha(int steps);
float sd_scheduler_alpha_cumprod(int step);

struct SdSchedule {
    std::vector<int> timesteps;
};

SdSchedule sd_make_schedule(int steps);

void sd_step(
        std::vector<float>& latent,
        const std::vector<float>& noise_pred,
        int t_index,
        int total_steps
);
