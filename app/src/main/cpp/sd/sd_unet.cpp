#include "sd_unet.h"
#include "sd_weight_loader.h"
#include <cmath>
#include <iostream>

static UnetModel g_unet;

const UnetModel& sd_unet_get_model() {
    return g_unet;
}

bool sd_unet_init(const std::string& model_dir) {
    // Optional: load real weights here
    auto tensors = load_weight_file(model_dir + "/unet_weights.bin");

    // Debug print (you can remove later)
    for (auto& t : tensors) {
        std::cout << t.name << " shape=[";
        for (auto d : t.shape) std::cout << d << ",";
        std::cout << "]\n";
    }

    // Simple 3-3-3 structure for now
    int num_down = 3;
    int num_mid  = 1;
    int num_up   = 3;

    g_unet.down_blocks.resize(num_down);
    g_unet.mid_blocks.resize(num_mid);
    g_unet.up_blocks.resize(num_up);

    auto init_block = [](UnetBlock& b, int in_c, int out_c) {
        int k = 3;
        int size = out_c * in_c * k * k;
        b.conv_w.resize(size);
        b.conv_b.resize(out_c);

        for (int i = 0; i < size; ++i) {
            b.conv_w[i] = std::sin(i * 0.001f);
        }
        for (int i = 0; i < out_c; ++i) {
            b.conv_b[i] = 0.0f;
        }
    };

    int c0 = g_unet.in_channels;
    int c1 = 320;
    int c2 = 640;
    int c3 = 1280;

    init_block(g_unet.down_blocks[0], c0, c1);
    init_block(g_unet.down_blocks[1], c1, c2);
    init_block(g_unet.down_blocks[2], c2, c3);

    init_block(g_unet.mid_blocks[0], c3, c3);

    init_block(g_unet.up_blocks[0], c3, c2);
    init_block(g_unet.up_blocks[1], c2, c1);
    init_block(g_unet.up_blocks[2], c1, g_unet.out_channels);

    return true;
}

void sd_unet_free() {
    // nothing yet
}

static void fake_conv_block(UnetLatent& x, const UnetBlock& b) {
    for (size_t i = 0; i < x.data.size(); ++i) {
        x.data[i] = std::tanh(x.data[i] * 0.9f);
    }
}

static UnetLatent fake_downsample(const UnetLatent& x) {
    UnetLatent y;
    y.c = x.c;
    y.h = x.h / 2;
    y.w = x.w / 2;
    y.data.resize(y.c * y.h * y.w);

    for (int c = 0; c < y.c; ++c) {
        for (int j = 0; j < y.h; ++j) {
            for (int i = 0; i < y.w; ++i) {
                float sum = 0.0f;
                for (int dj = 0; dj < 2; ++dj) {
                    for (int di = 0; di < 2; ++di) {
                        int src_j = j * 2 + dj;
                        int src_i = i * 2 + di;
                        int idx = c * (x.h * x.w) + src_j * x.w + src_i;
                        sum += x.data[idx];
                    }
                }
                int dst_idx = c * (y.h * y.w) + j * y.w + i;
                y.data[dst_idx] = sum * 0.25f;
            }
        }
    }
    return y;
}

static UnetLatent fake_upsample(const UnetLatent& x) {
    UnetLatent y;
    y.c = x.c;
    y.h = x.h * 2;
    y.w = x.w * 2;
    y.data.resize(y.c * y.h * y.w);

    for (int c = 0; c < y.c; ++c) {
        for (int j = 0; j < y.h; ++j) {
            for (int i = 0; i < y.w; ++i) {
                int src_j = j / 2;
                int src_i = i / 2;
                int src_idx = c * (x.h * x.w) + src_j * x.w + src_i;
                int dst_idx = c * (y.h * y.w) + j * y.w + i;
                y.data[dst_idx] = x.data[src_idx];
            }
        }
    }
    return y;
}

UnetLatent sd_unet_forward(
        const UnetLatent& x_in,
        const std::vector<float>& clip_emb,
        float t
) {
    UnetLatent x = x_in;
    std::vector<UnetLatent> skips;

    for (auto& b : g_unet.down_blocks) {
        fake_conv_block(x, b);
        skips.push_back(x);
        x = fake_downsample(x);
    }

    for (auto& b : g_unet.mid_blocks) {
        fake_conv_block(x, b);
    }

    for (size_t i = 0; i < g_unet.up_blocks.size(); ++i) {
        x = fake_upsample(x);
        if (!skips.empty()) {
            UnetLatent skip = skips.back();
            skips.pop_back();
            size_t n = std::min(x.data.size(), skip.data.size());
            for (size_t k = 0; k < n; ++k) {
                x.data[k] += skip.data[k];
            }
        }
        fake_conv_block(x, g_unet.up_blocks[i]);
    }

    return x;
}
