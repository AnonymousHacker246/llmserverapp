#include "sd_vae.h"
#include "sd_weight_loader.h"
#include <cmath>
#include <iostream>
#include <algorithm>

static VaeModel g_vae;

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

static const WeightTensor* find_tensor(
        const std::vector<WeightTensor>& tensors,
        const std::string& name
) {
    for (auto& t : tensors) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

static void init_conv(
        VaeConv& c,
        const WeightTensor* w,
        const WeightTensor* b
) {
    if (!w || w->shape.size() != 4) {
        std::cerr << "VAE conv weight missing or wrong shape\n";
        return;
    }

    int out_c = w->shape[0];
    int in_c  = w->shape[1];
    int k     = w->shape[2];

    c.in_channels  = in_c;
    c.out_channels = out_c;
    c.kernel_size  = k;
    c.weight       = w->data;

    c.bias.assign(out_c, 0.0f);
    if (b && b->data.size() == (size_t)out_c)
        c.bias = b->data;
}

static void conv2d(
        std::vector<float>& out,
        const std::vector<float>& x,
        int C_in, int H, int W,
        const VaeConv& c
) {
    int C_out = c.out_channels;
    int K     = c.kernel_size;

    out.assign((size_t)C_out * H * W, 0.0f);

    for (int co = 0; co < C_out; ++co) {
        for (int y = 0; y < H; ++y) {
            for (int x0 = 0; x0 < W; ++x0) {

                float sum = c.bias[co];

                for (int ci = 0; ci < C_in; ++ci) {
                    for (int ky = 0; ky < K; ++ky) {
                        for (int kx = 0; kx < K; ++kx) {

                            int iy = y + ky - K/2;
                            int ix = x0 + kx - K/2;
                            if (iy < 0 || iy >= H || ix < 0 || ix >= W)
                                continue;

                            size_t in_idx = (size_t)ci * H * W + iy * W + ix;
                            size_t w_idx  = (((size_t)co * C_in + ci) * K + ky) * K + kx;

                            sum += x[in_idx] * c.weight[w_idx];
                        }
                    }
                }

                out[(size_t)co * H * W + y * W + x0] = sum;
            }
        }
    }
}

static inline float silu(float x) {
    return x / (1.0f + std::exp(-x));
}

// -----------------------------------------------------------------------------
// Init
// -----------------------------------------------------------------------------

bool sd_vae_init(const std::string& model_dir) {
    auto tensors = load_weight_file(model_dir + "/vae_weights.bin");

    // These names must match your converter output
    init_conv(g_vae.conv1,
              find_tensor(tensors, "decoder.conv_in.weight"),
              find_tensor(tensors, "decoder.conv_in.bias"));

    init_conv(g_vae.up1,
              find_tensor(tensors, "decoder.up.0.conv.weight"),
              find_tensor(tensors, "decoder.up.0.conv.bias"));

    init_conv(g_vae.up2,
              find_tensor(tensors, "decoder.up.1.conv.weight"),
              find_tensor(tensors, "decoder.up.1.conv.bias"));

    init_conv(g_vae.final_conv,
              find_tensor(tensors, "decoder.conv_out.weight"),
              find_tensor(tensors, "decoder.conv_out.bias"));

    return true;
}

void sd_vae_free() {}

// -----------------------------------------------------------------------------
// Decode
// -----------------------------------------------------------------------------

SdImage sd_vae_decode(const std::vector<float>& latent, int out_w, int out_h) {
    SdImage img;
    img.width  = out_w;
    img.height = out_h;
    img.rgba.resize(out_w * out_h * 4);

    int H = out_h / 8;
    int W = out_w / 8;

    // 1. latent scaling
    std::vector<float> x = latent;
    for (float& v : x) v /= 0.18215f;

    // 2. conv_in
    std::vector<float> h1;
    conv2d(h1, x, 4, H, W, g_vae.conv1);
    for (float& v : h1) v = silu(v);

    // 3. upsample 1
    int H2 = H * 2;
    int W2 = W * 2;
    std::vector<float> up1((size_t)g_vae.conv1.out_channels * H2 * W2);

    for (int c = 0; c < g_vae.conv1.out_channels; ++c)
        for (int y = 0; y < H2; ++y)
            for (int x0 = 0; x0 < W2; ++x0)
                up1[(size_t)c * H2 * W2 + y * W2 + x0] =
                        h1[(size_t)c * H * W + (y/2) * W + (x0/2)];

    std::vector<float> h2;
    conv2d(h2, up1, g_vae.conv1.out_channels, H2, W2, g_vae.up1);
    for (float& v : h2) v = silu(v);

    // 4. upsample 2
    int H3 = H2 * 2;
    int W3 = W2 * 2;
    std::vector<float> up2((size_t)g_vae.up1.out_channels * H3 * W3);

    for (int c = 0; c < g_vae.up1.out_channels; ++c)
        for (int y = 0; y < H3; ++y)
            for (int x0 = 0; x0 < W3; ++x0)
                up2[(size_t)c * H3 * W3 + y * W3 + x0] =
                        h2[(size_t)c * H2 * W2 + (y/2) * W2 + (x0/2)];

    std::vector<float> h3;
    conv2d(h3, up2, g_vae.up1.out_channels, H3, W3, g_vae.up2);
    for (float& v : h3) v = silu(v);

    // 5. final conv → tanh
    std::vector<float> rgb;
    conv2d(rgb, h3, g_vae.up2.out_channels, H3, W3, g_vae.final_conv);

    for (float& v : rgb) v = std::tanh(v);

    // 6. convert to RGBA
    for (int y = 0; y < out_h; ++y) {
        for (int x0 = 0; x0 < out_w; ++x0) {

            int idx = (y * out_w + x0) * 4;

            int ry = y;
            int rx = x0;

            float r = rgb[(0 * out_h + ry) * out_w + rx];
            float g = rgb[(1 * out_h + ry) * out_w + rx];
            float b = rgb[(2 * out_h + ry) * out_w + rx];

            img.rgba[idx + 0] = (unsigned char)((r * 0.5f + 0.5f) * 255);
            img.rgba[idx + 1] = (unsigned char)((g * 0.5f + 0.5f) * 255);
            img.rgba[idx + 2] = (unsigned char)((b * 0.5f + 0.5f) * 255);
            img.rgba[idx + 3] = 255;
        }
    }

    return img;
}
