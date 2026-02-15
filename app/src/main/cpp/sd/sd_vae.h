#pragma once
#include <vector>
#include "sd_engine.h"

struct VaeModel {

};
// latent: [C,H,W] -> SdImage
bool sd_vae_init();
void sd_vae_free();

SdImage sd_vae_decode(const std::vector<float>& latent, int out_w, int out_h);
