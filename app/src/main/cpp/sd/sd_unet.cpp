#include "sd_unet.h"
#include "sd_weight_loader.h"
#include <cmath>
#include <iostream>

// -----------------------------------------------------------------------------
// Global UNet model
// -----------------------------------------------------------------------------

static UnetModel g_unet;

const UnetModel& sd_unet_get_model() {
    return g_unet;
}

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

static void init_block_from_tensor(
        UnetBlock& b,
        const WeightTensor* w,
        const WeightTensor* bias
) {
    if (!w) {
        std::cerr << "Missing conv weight tensor for block: " << (bias ? "with bias" : "no bias") << "\n";
        return;
    }

    // Expect shape: [out_c, in_c, k, k]
    if (w->shape.size() != 4) {
        std::cerr << "Unexpected conv weight shape for block\n";
        return;
    }

    int out_c = static_cast<int>(w->shape[0]);
    int in_c  = static_cast<int>(w->shape[1]);
    int k     = static_cast<int>(w->shape[2]); // assume square kernel

    b.in_channels  = in_c;
    b.out_channels = out_c;
    b.kernel_size  = k;

    size_t expected = static_cast<size_t>(out_c) * in_c * k * k;
    if (w->data.size() != expected) {
        std::cerr << "conv_w size mismatch for block\n";
        return;
    }

    b.conv_w = w->data; // copy weights

    b.conv_b.assign(out_c, 0.0f);
    if (bias && bias->data.size() == static_cast<size_t>(out_c)) {
        b.conv_b = bias->data;
    }
}

// -----------------------------------------------------------------------------
// Initialization
// -----------------------------------------------------------------------------

bool sd_unet_init(const std::string& model_dir) {
    auto tensors = load_weight_file(model_dir + "/unet_weights.bin");

    // Adjust these names to match your converter output
    const WeightTensor* down0_w = find_tensor(tensors, "down_blocks.0.conv.weight");
    const WeightTensor* down0_b = find_tensor(tensors, "down_blocks.0.conv.bias");

    const WeightTensor* down1_w = find_tensor(tensors, "down_blocks.1.conv.weight");
    const WeightTensor* down1_b = find_tensor(tensors, "down_blocks.1.conv.bias");

    const WeightTensor* down2_w = find_tensor(tensors, "down_blocks.2.conv.weight");
    const WeightTensor* down2_b = find_tensor(tensors, "down_blocks.2.conv.bias");

    const WeightTensor* mid_w = find_tensor(tensors, "mid_blocks.0.conv.weight");
    const WeightTensor* mid_b = find_tensor(tensors, "mid_blocks.0.conv.bias");

    const WeightTensor* up0_w = find_tensor(tensors, "up_blocks.0.conv.weight");
    const WeightTensor* up0_b = find_tensor(tensors, "up_blocks.0.conv.bias");

    const WeightTensor* up1_w = find_tensor(tensors, "up_blocks.1.conv.weight");
    const WeightTensor* up1_b = find_tensor(tensors, "up_blocks.1.conv.bias");

    const WeightTensor* up2_w = find_tensor(tensors, "up_blocks.2.conv.weight");
    const WeightTensor* up2_b = find_tensor(tensors, "up_blocks.2.conv.bias");

    g_unet.down_blocks.resize(3);
    g_unet.mid_blocks.resize(1);
    g_unet.up_blocks.resize(3);

    init_block_from_tensor(g_unet.down_blocks[0], down0_w, down0_b);
    init_block_from_tensor(g_unet.down_blocks[1], down1_w, down1_b);
    init_block_from_tensor(g_unet.down_blocks[2], down2_w, down2_b);

    init_block_from_tensor(g_unet.mid_blocks[0], mid_w, mid_b);

    init_block_from_tensor(g_unet.up_blocks[0], up0_w, up0_b);
    init_block_from_tensor(g_unet.up_blocks[1], up1_w, up1_b);
    init_block_from_tensor(g_unet.up_blocks[2], up2_w, up2_b);

    return true;
}

void sd_unet_free() {
    // nothing yet
}

// -----------------------------------------------------------------------------
// Core conv + block
// -----------------------------------------------------------------------------

static void conv2d_inplace(UnetLatent& x, const UnetBlock& b) {
    int C_in  = b.in_channels;
    int C_out = b.out_channels;
    int K     = b.kernel_size;
    int H     = x.h;
    int W     = x.w;

    if (C_in <= 0 || C_out <= 0 || K <= 0 || H <= 0 || W <= 0) {
        std::cerr << "Invalid conv2d params\n";
        return;
    }

    if (x.data.size() != static_cast<size_t>(C_in) * H * W) {
        std::cerr << "Latent size mismatch in conv2d_inplace\n";
        return;
    }

    size_t expected_w = static_cast<size_t>(C_out) * C_in * K * K;
    if (b.conv_w.size() != expected_w || b.conv_b.size() != static_cast<size_t>(C_out)) {
        std::cerr << "Weight size mismatch in conv2d_inplace\n";
        return;
    }

    std::vector<float> out(static_cast<size_t>(C_out) * H * W, 0.0f);

    for (int co = 0; co < C_out; ++co) {
        for (int y = 0; y < H; ++y) {
            for (int x0 = 0; x0 < W; ++x0) {
                float sum = b.conv_b[co];
                for (int ci = 0; ci < C_in; ++ci) {
                    for (int ky = 0; ky < K; ++ky) {
                        for (int kx = 0; kx < K; ++kx) {
                            int iy = y + ky - K / 2;
                            int ix = x0 + kx - K / 2;
                            if (iy < 0 || iy >= H || ix < 0 || ix >= W) continue;

                            size_t in_idx = static_cast<size_t>(ci) * H * W
                                            + static_cast<size_t>(iy) * W
                                            + static_cast<size_t>(ix);
                            size_t w_idx  = (((static_cast<size_t>(co) * C_in + ci) * K + ky) * K + kx);
                            sum += x.data[in_idx] * b.conv_w[w_idx];
                        }
                    }
                }
                size_t out_idx = static_cast<size_t>(co) * H * W
                                 + static_cast<size_t>(y) * W
                                 + static_cast<size_t>(x0);
                out[out_idx] = sum;
            }
        }
    }

    x.c = C_out;
    x.data.swap(out);
}

static void real_conv_block(UnetLatent& x, const UnetBlock& b) {
    conv2d_inplace(x, b);
    for (auto& v : x.data) {
        v = std::tanh(v); // simple nonlinearity
    }
}

// -----------------------------------------------------------------------------
// Forward
// -----------------------------------------------------------------------------

UnetLatent sd_unet_forward(
        const UnetLatent& x_in,
        const std::vector<float>& clip_emb,
        float t
) {
    (void)clip_emb; // not used yet
    (void)t;        // not used yet

    UnetLatent x = x_in;

    // Simple sequential stack: down -> mid -> up, all shape‑preserving
    for (auto& b : g_unet.down_blocks) {
        real_conv_block(x, b);
    }

    for (auto& b : g_unet.mid_blocks) {
        real_conv_block(x, b);
    }

    for (auto& b : g_unet.up_blocks) {
        real_conv_block(x, b);
    }

    return x;
}
