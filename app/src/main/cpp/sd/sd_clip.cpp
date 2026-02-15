#include "sd_clip.h"
#include <functional>
#include <cmath>
#include "sd_clip_tokenizer.h"

// ------------------------------------------------------------
// Globals
// ------------------------------------------------------------
static ClipTokenizer g_tok;
static ClipModel g_model;     // contains weights + transformer blocks
static ClipWeights& g_weights = g_model.weights; // alias for convenience

// ------------------------------------------------------------
// Forward declarations for helper functions
// ------------------------------------------------------------
static void clip_layernorm(float* x, int dim,
                           const std::vector<float>& gamma,
                           const std::vector<float>& beta);

static void clip_fake_attention(float* x, int dim);
static void clip_fake_mlp(float* x, int dim);

// ------------------------------------------------------------
// Public getters
// ------------------------------------------------------------
const ClipModel& sd_clip_get_model() {
    return g_model;
}

const ClipWeights& sd_clip_get_weights() {
    return g_weights;
}

// ------------------------------------------------------------
// Init
// ------------------------------------------------------------
bool sd_clip_init(const std::string& model_dir) {
    load_clip_tokenizer(model_dir, g_tok);

    // -----------------------------
    // Initialize embedding weights
    // -----------------------------
    g_weights.vocab_size = 50000;
    g_weights.dim = 768;

    g_weights.token_embedding.resize(g_weights.vocab_size * g_weights.dim);
    for (size_t i = 0; i < g_weights.token_embedding.size(); ++i) {
        g_weights.token_embedding[i] = std::sin(i * 0.001f);
    }

    g_weights.pos_embedding.resize(77 * g_weights.dim);
    for (size_t i = 0; i < g_weights.pos_embedding.size(); ++i) {
        g_weights.pos_embedding[i] = std::cos(i * 0.002f);
    }

    // -----------------------------
    // Initialize transformer blocks
    // -----------------------------
    g_model.num_layers = 24;
    g_model.blocks.resize(g_model.num_layers);

    for (int i = 0; i < g_model.num_layers; ++i) {
        auto& b = g_model.blocks[i];

        int dim = g_weights.dim;
        int mlp_dim = 3072;

        // LayerNorm
        b.ln1_gamma.resize(dim, 1.0f);
        b.ln1_beta.resize(dim, 0.0f);
        b.ln2_gamma.resize(dim, 1.0f);
        b.ln2_beta.resize(dim, 0.0f);

        // Attention weights (fake)
        b.attn_wq.resize(dim * dim);
        b.attn_wk.resize(dim * dim);
        b.attn_wv.resize(dim * dim);
        b.attn_wo.resize(dim * dim);

        // MLP weights (fake)
        b.mlp_w1.resize(dim * mlp_dim);
        b.mlp_w2.resize(mlp_dim * dim);

        // Deterministic fill
        for (size_t j = 0; j < b.attn_wq.size(); ++j) {
            b.attn_wq[j] = std::sin(j * 0.0001f);
            b.attn_wk[j] = std::cos(j * 0.0001f);
            b.attn_wv[j] = std::sin(j * 0.0002f);
            b.attn_wo[j] = std::cos(j * 0.0002f);
        }
        for (size_t j = 0; j < b.mlp_w1.size(); ++j) {
            b.mlp_w1[j] = std::sin(j * 0.0003f);
        }
        for (size_t j = 0; j < b.mlp_w2.size(); ++j) {
            b.mlp_w2[j] = std::cos(j * 0.0003f);
        }
    }

    return true;
}

void sd_clip_free() {
    // no-op for now
}

// ------------------------------------------------------------
// Encode
// ------------------------------------------------------------
std::vector<float> sd_clip_encode(const std::string& text) {
    std::vector<int> tokens = sd_clip_tokenize(text);

    const int dim = g_weights.dim;
    const int max_len = 77;

    // Sequence buffer
    std::vector<float> seq(max_len * dim, 0.0f);

    // --------------------------------------------------------
    // 1) Token embedding + positional embedding
    // --------------------------------------------------------
    for (int t = 0; t < max_len; ++t) {
        int id = tokens[t] % g_weights.vocab_size;

        const float* tok_emb = &g_weights.token_embedding[id * dim];
        const float* pos_emb = &g_weights.pos_embedding[t * dim];
        float* dst = &seq[t * dim];

        for (int i = 0; i < dim; ++i) {
            dst[i] = tok_emb[i] + pos_emb[i];
        }
    }

    // --------------------------------------------------------
    // 2) Transformer blocks
    // --------------------------------------------------------
    for (int layer = 0; layer < g_model.num_layers; ++layer) {
        auto& b = g_model.blocks[layer];

        for (int t = 0; t < max_len; ++t) {
            float* token = &seq[t * dim];

            // LN + fake attention
            clip_layernorm(token, dim, b.ln1_gamma, b.ln1_beta);
            clip_fake_attention(token, dim);

            // LN + fake MLP
            clip_layernorm(token, dim, b.ln2_gamma, b.ln2_beta);
            clip_fake_mlp(token, dim);
        }
    }

    // --------------------------------------------------------
    // 3) Mean pool AFTER transformer
    // --------------------------------------------------------
    std::vector<float> out(dim, 0.0f);

    for (int t = 0; t < max_len; ++t) {
        const float* src = &seq[t * dim];
        for (int i = 0; i < dim; ++i) {
            out[i] += src[i];
        }
    }
    for (int i = 0; i < dim; ++i) {
        out[i] /= max_len;
    }

    return out;
}

// ------------------------------------------------------------
// Tokenizer wrapper
// ------------------------------------------------------------
std::vector<int> sd_clip_tokenize(const std::string& text) {
    return clip_bpe_tokenize(g_tok, text);
}

// ------------------------------------------------------------
// Helpers
// ------------------------------------------------------------
static void clip_layernorm(float* x, int dim,
                           const std::vector<float>& gamma,
                           const std::vector<float>& beta)
{
    float mean = 0.0f;
    for (int i = 0; i < dim; ++i) mean += x[i];
    mean /= dim;

    float var = 0.0f;
    for (int i = 0; i < dim; ++i) {
        float d = x[i] - mean;
        var += d * d;
    }
    var /= dim;

    float inv = 1.0f / std::sqrt(var + 1e-5f);

    for (int i = 0; i < dim; ++i) {
        x[i] = (x[i] - mean) * inv * gamma[i] + beta[i];
    }
}

static void clip_fake_attention(float* x, int dim) {
    for (int i = 0; i < dim; ++i) {
        x[i] += std::sin(i * 0.01f);
    }
}

static void clip_fake_mlp(float* x, int dim) {
    for (int i = 0; i < dim; ++i) {
        x[i] = std::tanh(x[i] * 0.5f);
    }
}
