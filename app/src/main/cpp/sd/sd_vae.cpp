#include "sd_vae.h"
#include "sd_weight_loader.h"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOGVAEI(...) __android_log_print(ANDROID_LOG_INFO,  "SD_VAE", __VA_ARGS__)
#define LOGVAEE(...) __android_log_print(ANDROID_LOG_ERROR, "SD_VAE", __VA_ARGS__)

VaeModel g_vae;

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
        const WeightTensor* b,
        const char* name
) {
    if (!w || w->shape.size() != 4) {
        LOGVAEE("init_conv(%s): weight missing or wrong shape", name);
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

    LOGVAEI("init_conv(%s): in=%d out=%d k=%d w_size=%zu b_size=%zu",
            name, c.in_channels, c.out_channels, c.kernel_size,
            c.weight.size(), c.bias.size());
}

static void init_norm(
        VaeNorm& n,
        const WeightTensor* w,
        const WeightTensor* b,
        const char* name
) {
    if (!w || !b || w->data.size() != b->data.size()) {
        LOGVAEE("init_norm(%s): missing or size mismatch", name);
        return;
    }
    int C = (int)w->data.size();
    n.num_channels = C;
    n.weight = w->data;
    n.bias   = b->data;
    n.num_groups = 32;
    n.eps = 1e-5f;

    LOGVAEI("init_norm(%s): C=%d w_size=%zu b_size=%zu",
            name, C, n.weight.size(), n.bias.size());
}

static inline float silu(float x) {
    return x / (1.0f + std::exp(-x));
}

static void conv2d(
        std::vector<float>& out,
        const std::vector<float>& x,
        int C_in, int H, int W,
        const VaeConv& c
) {
    int C_out = c.out_channels;
    int K     = c.kernel_size;

    LOGVAEI("conv2d: C_in=%d H=%d W=%d -> C_out=%d K=%d",
            C_in, H, W, C_out, K);

    if (C_in != c.in_channels) {
        LOGVAEE("conv2d: C_in mismatch, got %d expected %d",
                C_in, c.in_channels);
        out.clear();
        return;
    }

    size_t expected_w = (size_t)C_out * C_in * K * K;
    if (c.weight.size() != expected_w) {
        LOGVAEE("conv2d: weight size mismatch, got %zu expected %zu",
                c.weight.size(), expected_w);
        out.clear();
        return;
    }

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

    LOGVAEI("conv2d: done, out size=%zu", out.size());
}

static void apply_groupnorm(
        std::vector<float>& out,
        const std::vector<float>& x,
        int C, int H, int W,
        const VaeNorm& n
) {
    int G = n.num_groups;
    int N = H * W;
    if (C % G != 0) {
        LOGVAEE("GroupNorm: C=%d not divisible by G=%d", C, G);
        out.clear();
        return;
    }
    int Cg = C / G;

    out.resize(x.size());

    for (int g = 0; g < G; ++g) {
        for (int i = 0; i < N; ++i) {
            double mean = 0.0;
            double var  = 0.0;

            for (int c = 0; c < Cg; ++c) {
                int ch = g * Cg + c;
                size_t idx = (size_t)ch * N + i;
                float v = x[idx];
                mean += v;
                var  += v * v;
            }
            mean /= Cg;
            var = var / Cg - mean * mean;
            if (var < 0.0) var = 0.0;
            double inv_std = 1.0 / std::sqrt(var + n.eps);

            for (int c = 0; c < Cg; ++c) {
                int ch = g * Cg + c;
                size_t idx = (size_t)ch * N + i;
                float v = x[idx];
                float normed = (float)((v - mean) * inv_std);
                float gamma = n.weight[ch];
                float beta  = n.bias[ch];
                out[idx] = normed * gamma + beta;
            }
        }
    }
}

static void resblock_forward(
        std::vector<float>& out,
        const std::vector<float>& x,
        int C, int H, int W,
        const VaeResBlock& rb
) {
    LOGVAEI("resblock_forward: C=%d H=%d W=%d has_shortcut=%d",
            C, H, W, rb.has_shortcut ? 1 : 0);

    std::vector<float> h_norm1;
    apply_groupnorm(h_norm1, x, C, H, W, rb.norm1);
    if (h_norm1.empty()) {
        LOGVAEE("resblock_forward: norm1 failed");
        out.clear();
        return;
    }
    for (float& v : h_norm1) v = silu(v);

    std::vector<float> h_conv1;
    conv2d(h_conv1, h_norm1, C, H, W, rb.conv1);
    if (h_conv1.empty()) {
        LOGVAEE("resblock_forward: conv1 failed");
        out.clear();
        return;
    }
    int C1 = rb.conv1.out_channels;

    std::vector<float> h_norm2;
    apply_groupnorm(h_norm2, h_conv1, C1, H, W, rb.norm2);
    if (h_norm2.empty()) {
        LOGVAEE("resblock_forward: norm2 failed");
        out.clear();
        return;
    }
    for (float& v : h_norm2) v = silu(v);

    std::vector<float> h_conv2;
    conv2d(h_conv2, h_norm2, C1, H, W, rb.conv2);
    if (h_conv2.empty()) {
        LOGVAEE("resblock_forward: conv2 failed");
        out.clear();
        return;
    }
    int Cout = rb.conv2.out_channels;

    std::vector<float> shortcut;
    if (rb.has_shortcut) {
        conv2d(shortcut, x, C, H, W, rb.nin_shortcut);
        if (shortcut.empty()) {
            LOGVAEE("resblock_forward: nin_shortcut failed");
            out.clear();
            return;
        }
    } else {
        shortcut = x;
    }

    if ((int)shortcut.size() != Cout * H * W) {
        LOGVAEE("resblock_forward: shortcut size mismatch, got %zu expected %d",
                shortcut.size(), Cout * H * W);
        out.clear();
        return;
    }

    out.resize(h_conv2.size());
    for (size_t i = 0; i < h_conv2.size(); ++i) {
        out[i] = h_conv2[i] + shortcut[i];
    }
}

static void upsample_conv_forward(
        std::vector<float>& out,
        const std::vector<float>& x,
        int C, int H, int W,
        const VaeConv& up_conv
) {
    LOGVAEI("upsample_conv_forward: C=%d H=%d W=%d", C, H, W);

    int H2 = H * 2;
    int W2 = W * 2;
    std::vector<float> up;
    up.assign((size_t)C * H2 * W2, 0.0f);

    for (int c = 0; c < C; ++c) {
        for (int y = 0; y < H2; ++y) {
            for (int x0 = 0; x0 < W2; ++x0) {
                int sy = y / 2;
                int sx = x0 / 2;
                up[(size_t)c * H2 * W2 + y * W2 + x0] =
                        x[(size_t)c * H * W + sy * W + sx];
            }
        }
    }

    conv2d(out, up, C, H2, W2, up_conv);
}

// -----------------------------------------------------------------------------
// Init
// -----------------------------------------------------------------------------

bool sd_vae_init(const std::string& model_dir) {
    LOGVAEI("sd_vae_init: model_dir=%s", model_dir.c_str());
    auto tensors = load_weight_file(model_dir + "/vae_weights.bin");
    LOGVAEI("sd_vae_init: loaded %zu tensors", tensors.size());

    // conv_in
    auto *conv_in_w  = find_tensor(tensors, "decoder.conv_in.weight");
    auto *conv_in_b  = find_tensor(tensors, "decoder.conv_in.bias");
    init_conv(g_vae.conv_in, conv_in_w, conv_in_b, "decoder.conv_in");

    // mid block 1
    init_norm(
            g_vae.mid_block1.norm1,
            find_tensor(tensors, "decoder.mid.block_1.norm1.weight"),
            find_tensor(tensors, "decoder.mid.block_1.norm1.bias"),
            "decoder.mid.block_1.norm1"
    );
    init_norm(
            g_vae.mid_block1.norm2,
            find_tensor(tensors, "decoder.mid.block_1.norm2.weight"),
            find_tensor(tensors, "decoder.mid.block_1.norm2.bias"),
            "decoder.mid.block_1.norm2"
    );
    init_conv(
            g_vae.mid_block1.conv1,
            find_tensor(tensors, "decoder.mid.block_1.conv1.weight"),
            find_tensor(tensors, "decoder.mid.block_1.conv1.bias"),
            "decoder.mid.block_1.conv1"
    );
    init_conv(
            g_vae.mid_block1.conv2,
            find_tensor(tensors, "decoder.mid.block_1.conv2.weight"),
            find_tensor(tensors, "decoder.mid.block_1.conv2.bias"),
            "decoder.mid.block_1.conv2"
    );
    {
        auto *sc_w = find_tensor(tensors, "decoder.mid.block_1.nin_shortcut.weight");
        auto *sc_b = find_tensor(tensors, "decoder.mid.block_1.nin_shortcut.bias");
        if (sc_w && sc_b) {
            g_vae.mid_block1.has_shortcut = true;
            init_conv(g_vae.mid_block1.nin_shortcut, sc_w, sc_b,
                      "decoder.mid.block_1.nin_shortcut");
        } else {
            g_vae.mid_block1.has_shortcut = false;
            LOGVAEI("init_resblock: decoder.mid.block_1 has_shortcut=0");
        }
    }

    // mid block 2
    init_norm(
            g_vae.mid_block2.norm1,
            find_tensor(tensors, "decoder.mid.block_2.norm1.weight"),
            find_tensor(tensors, "decoder.mid.block_2.norm1.bias"),
            "decoder.mid.block_2.norm1"
    );
    init_norm(
            g_vae.mid_block2.norm2,
            find_tensor(tensors, "decoder.mid.block_2.norm2.weight"),
            find_tensor(tensors, "decoder.mid.block_2.norm2.bias"),
            "decoder.mid.block_2.norm2"
    );
    init_conv(
            g_vae.mid_block2.conv1,
            find_tensor(tensors, "decoder.mid.block_2.conv1.weight"),
            find_tensor(tensors, "decoder.mid.block_2.conv1.bias"),
            "decoder.mid.block_2.conv1"
    );
    init_conv(
            g_vae.mid_block2.conv2,
            find_tensor(tensors, "decoder.mid.block_2.conv2.weight"),
            find_tensor(tensors, "decoder.mid.block_2.conv2.bias"),
            "decoder.mid.block_2.conv2"
    );
    {
        auto *sc_w = find_tensor(tensors, "decoder.mid.block_2.nin_shortcut.weight");
        auto *sc_b = find_tensor(tensors, "decoder.mid.block_2.nin_shortcut.bias");
        if (sc_w && sc_b) {
            g_vae.mid_block2.has_shortcut = true;
            init_conv(g_vae.mid_block2.nin_shortcut, sc_w, sc_b,
                      "decoder.mid.block_2.nin_shortcut");
        } else {
            g_vae.mid_block2.has_shortcut = false;
            LOGVAEI("init_resblock: decoder.mid.block_2 has_shortcut=0");
        }
    }

    auto init_upblock = [&](VaeUpBlock& ub,
                            const std::string& prefix,
                            bool has_upsample) {
        // block0
        init_norm(
                ub.block0.norm1,
                find_tensor(tensors, prefix + ".block.0.norm1.weight"),
                find_tensor(tensors, prefix + ".block.0.norm1.bias"),
                (prefix + ".block.0.norm1").c_str()
        );
        init_norm(
                ub.block0.norm2,
                find_tensor(tensors, prefix + ".block.0.norm2.weight"),
                find_tensor(tensors, prefix + ".block.0.norm2.bias"),
                (prefix + ".block.0.norm2").c_str()
        );
        init_conv(
                ub.block0.conv1,
                find_tensor(tensors, prefix + ".block.0.conv1.weight"),
                find_tensor(tensors, prefix + ".block.0.conv1.bias"),
                (prefix + ".block.0.conv1").c_str()
        );
        init_conv(
                ub.block0.conv2,
                find_tensor(tensors, prefix + ".block.0.conv2.weight"),
                find_tensor(tensors, prefix + ".block.0.conv2.bias"),
                (prefix + ".block.0.conv2").c_str()
        );
        {
            auto *sc_w = find_tensor(tensors, prefix + ".block.0.nin_shortcut.weight");
            auto *sc_b = find_tensor(tensors, prefix + ".block.0.nin_shortcut.bias");
            if (sc_w && sc_b) {
                ub.block0.has_shortcut = true;
                init_conv(ub.block0.nin_shortcut, sc_w, sc_b,
                          (prefix + ".block.0.nin_shortcut").c_str());
            } else {
                ub.block0.has_shortcut = false;
                LOGVAEI("init_resblock: %s.block.0 has_shortcut=0", prefix.c_str());
            }
        }

        // block1
        init_norm(
                ub.block1.norm1,
                find_tensor(tensors, prefix + ".block.1.norm1.weight"),
                find_tensor(tensors, prefix + ".block.1.norm1.bias"),
                (prefix + ".block.1.norm1").c_str()
        );
        init_norm(
                ub.block1.norm2,
                find_tensor(tensors, prefix + ".block.1.norm2.weight"),
                find_tensor(tensors, prefix + ".block.1.norm2.bias"),
                (prefix + ".block.1.norm2").c_str()
        );
        init_conv(
                ub.block1.conv1,
                find_tensor(tensors, prefix + ".block.1.conv1.weight"),
                find_tensor(tensors, prefix + ".block.1.conv1.bias"),
                (prefix + ".block.1.conv1").c_str()
        );
        init_conv(
                ub.block1.conv2,
                find_tensor(tensors, prefix + ".block.1.conv2.weight"),
                find_tensor(tensors, prefix + ".block.1.conv2.bias"),
                (prefix + ".block.1.conv2").c_str()
        );
        {
            auto *sc_w = find_tensor(tensors, prefix + ".block.1.nin_shortcut.weight");
            auto *sc_b = find_tensor(tensors, prefix + ".block.1.nin_shortcut.bias");
            if (sc_w && sc_b) {
                ub.block1.has_shortcut = true;
                init_conv(ub.block1.nin_shortcut, sc_w, sc_b,
                          (prefix + ".block.1.nin_shortcut").c_str());
            } else {
                ub.block1.has_shortcut = false;
                LOGVAEI("init_resblock: %s.block.1 has_shortcut=0", prefix.c_str());
            }
        }

        // block2
        init_norm(
                ub.block2.norm1,
                find_tensor(tensors, prefix + ".block.2.norm1.weight"),
                find_tensor(tensors, prefix + ".block.2.norm1.bias"),
                (prefix + ".block.2.norm1").c_str()
        );
        init_norm(
                ub.block2.norm2,
                find_tensor(tensors, prefix + ".block.2.norm2.weight"),
                find_tensor(tensors, prefix + ".block.2.norm2.bias"),
                (prefix + ".block.2.norm2").c_str()
        );
        init_conv(
                ub.block2.conv1,
                find_tensor(tensors, prefix + ".block.2.conv1.weight"),
                find_tensor(tensors, prefix + ".block.2.conv1.bias"),
                (prefix + ".block.2.conv1").c_str()
        );
        init_conv(
                ub.block2.conv2,
                find_tensor(tensors, prefix + ".block.2.conv2.weight"),
                find_tensor(tensors, prefix + ".block.2.conv2.bias"),
                (prefix + ".block.2.conv2").c_str()
        );
        {
            auto *sc_w = find_tensor(tensors, prefix + ".block.2.nin_shortcut.weight");
            auto *sc_b = find_tensor(tensors, prefix + ".block.2.nin_shortcut.bias");
            if (sc_w && sc_b) {
                ub.block2.has_shortcut = true;
                init_conv(ub.block2.nin_shortcut, sc_w, sc_b,
                          (prefix + ".block.2.nin_shortcut").c_str());
            } else {
                ub.block2.has_shortcut = false;
                LOGVAEI("init_resblock: %s.block.2 has_shortcut=0", prefix.c_str());
            }
        }

        ub.has_upsample = has_upsample;
        if (has_upsample) {
            auto *up_w = find_tensor(tensors, prefix + ".upsample.conv.weight");
            auto *up_b = find_tensor(tensors, prefix + ".upsample.conv.bias");
            init_conv(ub.upsample_conv, up_w, up_b,
                      (prefix + ".upsample.conv").c_str());
            LOGVAEI("init_upblock: %s has_upsample=1", prefix.c_str());
        } else {
            LOGVAEI("init_upblock: %s has_upsample=0", prefix.c_str());
        }
    };

    init_upblock(g_vae.up0, "decoder.up.0", false);
    init_upblock(g_vae.up1, "decoder.up.1", true);
    init_upblock(g_vae.up2, "decoder.up.2", true);
    init_upblock(g_vae.up3, "decoder.up.3", true);

    // norm_out
    init_norm(
            g_vae.norm_out,
            find_tensor(tensors, "decoder.norm_out.weight"),
            find_tensor(tensors, "decoder.norm_out.bias"),
            "decoder.norm_out"
    );

    // conv_out
    auto *conv_out_w = find_tensor(tensors, "decoder.conv_out.weight");
    auto *conv_out_b = find_tensor(tensors, "decoder.conv_out.bias");
    init_conv(g_vae.conv_out, conv_out_w, conv_out_b, "decoder.conv_out");

    LOGVAEI("sd_vae_init: conv_in  in=%d out=%d k=%d",
            g_vae.conv_in.in_channels,
            g_vae.conv_in.out_channels,
            g_vae.conv_in.kernel_size);

    LOGVAEI("sd_vae_init: conv_out in=%d out=%d k=%d",
            g_vae.conv_out.in_channels,
            g_vae.conv_out.out_channels,
            g_vae.conv_out.kernel_size);

    bool ok = g_vae.conv_in.out_channels  != 0 &&
              g_vae.conv_out.out_channels != 0;

    LOGVAEI("sd_vae_init: done, ok=%d", ok ? 1 : 0);
    return ok;
}

void sd_vae_free() {
    LOGVAEI("sd_vae_free");
}

// -----------------------------------------------------------------------------
// Decode
// -----------------------------------------------------------------------------

SdImage sd_vae_decode(const std::vector<float>& latent, int out_w, int out_h) {
    LOGVAEI("sd_vae_decode: latent size=%zu out_w=%d out_h=%d",
            latent.size(), out_w, out_h);

    SdImage img;

    if (latent.size() % 4 != 0) {
        LOGVAEE("VAE decode: latent size not divisible by 4, got %zu",
                latent.size());
        return {};
    }
    int HW = (int)(latent.size() / 4);
    int H = (int)std::sqrt(HW);
    int W = HW / H;
    if (H * W * 4 != (int)latent.size()) {
        LOGVAEE("VAE decode: latent shape mismatch, H=%d W=%d size=%zu",
                H, W, latent.size());
        return {};
    }

    LOGVAEI("VAE decode: inferred latent shape C=4 H=%d W=%d", H, W);

    // 1) scale latent
    std::vector<float> x = latent;
    for (float& v : x) v /= 0.18215f;

    // 2) conv_in (4 -> C0)
    std::vector<float> h;
    conv2d(h, x, 4, H, W, g_vae.conv_in);
    if (h.empty()) {
        LOGVAEE("VAE: empty after conv_in");
        return {};
    }
    int C = g_vae.conv_in.out_channels;
    LOGVAEI("VAE: after conv_in C=%d H=%d W=%d size=%zu", C, H, W, h.size());

    // 3) mid block_1
    std::vector<float> h_mid1;
    resblock_forward(h_mid1, h, C, H, W, g_vae.mid_block1);
    if (h_mid1.empty()) {
        LOGVAEE("VAE: empty after mid_block1");
        return {};
    }
    C = g_vae.mid_block1.conv2.out_channels;

    // 4) mid block_2
    std::vector<float> h_mid2;
    resblock_forward(h_mid2, h_mid1, C, H, W, g_vae.mid_block2);
    if (h_mid2.empty()) {
        LOGVAEE("VAE: empty after mid_block2");
        return {};
    }
    C = g_vae.mid_block2.conv2.out_channels;

    // 5) up.0 (no upsample)
    std::vector<float> h_up0_0;
    resblock_forward(h_up0_0, h_mid2, C, H, W, g_vae.up0.block0);
    C = g_vae.up0.block0.conv2.out_channels;

    std::vector<float> h_up0_1;
    resblock_forward(h_up0_1, h_up0_0, C, H, W, g_vae.up0.block1);
    C = g_vae.up0.block1.conv2.out_channels;

    std::vector<float> h_up0_2;
    resblock_forward(h_up0_2, h_up0_1, C, H, W, g_vae.up0.block2);
    C = g_vae.up0.block2.conv2.out_channels;

    // 6) up.1 (upsample to 128x128)
    std::vector<float> h_up1_in = h_up0_2;
    int H1 = H, W1 = W;

    if (g_vae.up1.has_upsample) {
        std::vector<float> h_up1_us;
        upsample_conv_forward(h_up1_us, h_up1_in, C, H1, W1, g_vae.up1.upsample_conv);
        H1 *= 2;
        W1 *= 2;
        h_up1_in.swap(h_up1_us);
        C = g_vae.up1.upsample_conv.out_channels;
    }

    std::vector<float> h_up1_0;
    resblock_forward(h_up1_0, h_up1_in, C, H1, W1, g_vae.up1.block0);
    C = g_vae.up1.block0.conv2.out_channels;

    std::vector<float> h_up1_1;
    resblock_forward(h_up1_1, h_up1_0, C, H1, W1, g_vae.up1.block1);
    C = g_vae.up1.block1.conv2.out_channels;

    std::vector<float> h_up1_2;
    resblock_forward(h_up1_2, h_up1_1, C, H1, W1, g_vae.up1.block2);
    C = g_vae.up1.block2.conv2.out_channels;

    // 7) up.2 (upsample to 256x256)
    std::vector<float> h_up2_in = h_up1_2;
    int H2 = H1, W2 = W1;

    if (g_vae.up2.has_upsample) {
        std::vector<float> h_up2_us;
        upsample_conv_forward(h_up2_us, h_up2_in, C, H2, W2, g_vae.up2.upsample_conv);
        H2 *= 2;
        W2 *= 2;
        h_up2_in.swap(h_up2_us);
        C = g_vae.up2.upsample_conv.out_channels;
    }

    std::vector<float> h_up2_0;
    resblock_forward(h_up2_0, h_up2_in, C, H2, W2, g_vae.up2.block0);
    C = g_vae.up2.block0.conv2.out_channels;

    std::vector<float> h_up2_1;
    resblock_forward(h_up2_1, h_up2_0, C, H2, W2, g_vae.up2.block1);
    C = g_vae.up2.block1.conv2.out_channels;

    std::vector<float> h_up2_2;
    resblock_forward(h_up2_2, h_up2_1, C, H2, W2, g_vae.up2.block2);
    C = g_vae.up2.block2.conv2.out_channels;

    // 8) up.3 (upsample to 512x512)
    std::vector<float> h_up3_in = h_up2_2;
    int H3 = H2, W3 = W2;

    if (g_vae.up3.has_upsample) {
        std::vector<float> h_up3_us;
        upsample_conv_forward(h_up3_us, h_up3_in, C, H3, W3, g_vae.up3.upsample_conv);
        H3 *= 2;
        W3 *= 2;
        h_up3_in.swap(h_up3_us);
        C = g_vae.up3.upsample_conv.out_channels;
    }

    std::vector<float> h_up3_0;
    resblock_forward(h_up3_0, h_up3_in, C, H3, W3, g_vae.up3.block0);
    C = g_vae.up3.block0.conv2.out_channels;

    std::vector<float> h_up3_1;
    resblock_forward(h_up3_1, h_up3_0, C, H3, W3, g_vae.up3.block1);
    C = g_vae.up3.block1.conv2.out_channels;

    std::vector<float> h_up3_2;
    resblock_forward(h_up3_2, h_up3_1, C, H3, W3, g_vae.up3.block2);
    C = g_vae.up3.block2.conv2.out_channels;

    // 9) norm_out
    std::vector<float> h_norm_out;
    apply_groupnorm(h_norm_out, h_up3_2, C, H3, W3, g_vae.norm_out);
    if (h_norm_out.empty()) {
        LOGVAEE("VAE: empty after norm_out");
        return {};
    }
    for (float& v : h_norm_out) v = silu(v);

    // 10) conv_out -> 3xH3xW3
    std::vector<float> rgb;
    conv2d(rgb, h_norm_out, C, H3, W3, g_vae.conv_out);
    if (rgb.empty()) {
        LOGVAEE("VAE: rgb empty after conv_out");
        return {};
    }
    for (float& v : rgb) v = std::tanh(v);

    if (rgb.size() != (size_t)3 * H3 * W3) {
        LOGVAEE("VAE: rgb size mismatch, got %zu expected %zu",
                rgb.size(), (size_t)3 * H3 * W3);
        return {};
    }

    img.width  = W3;
    img.height = H3;
    img.rgba.resize((size_t)W3 * H3 * 4);
    LOGVAEI("VAE: writing RGBA %dx%d (size=%zu)",
            W3, H3, img.rgba.size());

    size_t plane = (size_t)H3 * W3;
    for (int y = 0; y < H3; ++y) {
        for (int x0 = 0; x0 < W3; ++x0) {
            int idx = (y * W3 + x0) * 4;
            size_t p = (size_t)y * W3 + x0;

            float r = rgb[p + 0 * plane];
            float g = rgb[p + 1 * plane];
            float b = rgb[p + 2 * plane];

            img.rgba[idx + 0] = (unsigned char)((r * 0.5f + 0.5f) * 255);
            img.rgba[idx + 1] = (unsigned char)((g * 0.5f + 0.5f) * 255);
            img.rgba[idx + 2] = (unsigned char)((b * 0.5f + 0.5f) * 255);
            img.rgba[idx + 3] = 255;
        }
    }

    LOGVAEI("sd_vae_decode: done, w=%d h=%d rgba=%zu",
            img.width, img.height, img.rgba.size());
    return img;
}
