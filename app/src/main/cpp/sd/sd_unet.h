#pragma once
#include <vector>
#include <string>

struct UnetLatent {
    int c, h, w;
    std::vector<float> data;
};

struct UnetBlock {
    std::vector<float> conv_w;
    std::vector<float> conv_b;
};

struct UnetModel {
    int in_channels = 4;
    int model_channels = 320;
    int out_channels = 4;

    std::vector<UnetBlock> down_blocks;
    std::vector<UnetBlock> mid_blocks;
    std::vector<UnetBlock> up_blocks;
};

// DECLARATIONS ONLY — NO CODE HERE
bool sd_unet_init(const std::string& model_dir);
void sd_unet_free();

UnetLatent sd_unet_forward(
        const UnetLatent& x,
        const std::vector<float>& clip_emb,
        float t
);

const UnetModel& sd_unet_get_model();
