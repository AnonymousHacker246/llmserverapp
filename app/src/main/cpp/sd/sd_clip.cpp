#include "sd_clip.h"
#include "sd_clip_tokenizer.h"
#include "sd_weight_loader.h"

#include <cmath>
#include <vector>
#include <string>
#include <iostream>

// ------------------------------------------------------------
// Globals
// ------------------------------------------------------------
static ClipTokenizer g_tok;
static ClipModel g_model;                 // contains weights + transformer blocks
static ClipWeights& g_weights = g_model.weights; // alias

// ------------------------------------------------------------
// Helpers
// ------------------------------------------------------------
static const WeightTensor* find_tensor(
        const std::vector<WeightTensor>& tensors,
        const std::string& name
) {
    for (auto& t : tensors) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

static void clip_layernorm_vec(
        float* x,
        int dim,
        const std::vector<float>& gamma,
        const std::vector<float>& beta
) {
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

static void matmul(
        std::vector<float>& out,
        const std::vector<float>& x,   // [T, D_in]
        const std::vector<float>& w,   // [D_in, D_out]
        int T,
        int D_in,
        int D_out
) {
    out.assign((size_t)T * D_out, 0.0f);

    for (int t = 0; t < T; ++t) {
        const float* x_row = &x[t * D_in];
        float* y_row = &out[t * D_out];

        for (int j = 0; j < D_out; ++j) {
            float sum = 0.0f;
            for (int i = 0; i < D_in; ++i) {
                sum += x_row[i] * w[i * D_out + j];
            }
            y_row[j] = sum;
        }
    }
}

static void softmax_rows(std::vector<float>& x, int T, int D) {
    for (int t = 0; t < T; ++t) {
        float* row = &x[t * D];

        float maxv = row[0];
        for (int i = 1; i < D; ++i) maxv = std::max(maxv, row[i]);

        float sum = 0.0f;
        for (int i = 0; i < D; ++i) {
            row[i] = std::exp(row[i] - maxv);
            sum += row[i];
        }
        float inv = 1.0f / (sum + 1e-9f);
        for (int i = 0; i < D; ++i) row[i] *= inv;
    }
}

static void clip_attention(
        std::vector<float>& seq, // [T, D]
        int T,
        int D,
        const ClipTransformerBlock& b
) {
    // Q, K, V: [T, D]
    std::vector<float> Q, K, V;
    matmul(Q, seq, b.attn_wq, T, D, D);
    matmul(K, seq, b.attn_wk, T, D, D);
    matmul(V, seq, b.attn_wv, T, D, D);

    // scores = Q * K^T / sqrt(D)
    std::vector<float> scores((size_t)T * T, 0.0f);
    float scale = 1.0f / std::sqrt((float)D);

    for (int t = 0; t < T; ++t) {
        for (int s = 0; s < T; ++s) {
            float sum = 0.0f;
            const float* q = &Q[t * D];
            const float* k = &K[s * D];
            for (int i = 0; i < D; ++i) {
                sum += q[i] * k[i];
            }
            scores[t * T + s] = sum * scale;
        }
    }

    // softmax over last dim
    softmax_rows(scores, T, T);

    // context = scores * V
    std::vector<float> context((size_t)T * D, 0.0f);
    for (int t = 0; t < T; ++t) {
        float* ctx = &context[t * D];
        for (int s = 0; s < T; ++s) {
            float w = scores[t * T + s];
            const float* v = &V[s * D];
            for (int i = 0; i < D; ++i) {
                ctx[i] += w * v[i];
            }
        }
    }

    // out = context * Wo
    std::vector<float> out;
    matmul(out, context, b.attn_wo, T, D, D);

    // residual
    for (int t = 0; t < T; ++t) {
        float* dst = &seq[t * D];
        const float* src = &out[t * D];
        for (int i = 0; i < D; ++i) {
            dst[i] += src[i];
        }
    }
}

static void clip_mlp(
        std::vector<float>& seq, // [T, D]
        int T,
        int D,
        const ClipTransformerBlock& b
) {
    int M = (int)(b.mlp_w1.size() / D); // hidden dim

    // hidden = seq * W1  (gelu)
    std::vector<float> hidden;
    matmul(hidden, seq, b.mlp_w1, T, D, M);

    for (int t = 0; t < T; ++t) {
        float* h = &hidden[t * M];
        for (int i = 0; i < M; ++i) {
            float x = h[i];
            // GELU
            h[i] = 0.5f * x * (1.0f + std::tanh(std::sqrt(2.0f / M_PI) * (x + 0.044715f * x * x * x)));
        }
    }

    // out = hidden * W2
    std::vector<float> out;
    matmul(out, hidden, b.mlp_w2, T, M, D);

    // residual
    for (int t = 0; t < T; ++t) {
        float* dst = &seq[t * D];
        const float* src = &out[t * D];
        for (int i = 0; i < D; ++i) {
            dst[i] += src[i];
        }
    }
}

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
// Init (real weights)
// ------------------------------------------------------------
bool sd_clip_init(const std::string& model_dir) {
    load_clip_tokenizer(model_dir, g_tok);

    auto tensors = load_weight_file(model_dir + "/clip_weights.bin");

    // token embedding
    const WeightTensor* tok = find_tensor(tensors, "text_model.embeddings.token_embedding.weight");
    if (!tok || tok->shape.size() != 2) {
        std::cerr << "Missing or bad token_embedding\n";
        return false;
    }
    g_weights.vocab_size = tok->shape[0];
    g_weights.dim        = tok->shape[1];
    g_weights.token_embedding = tok->data;

    // positional embedding
    const WeightTensor* pos = find_tensor(tensors, "text_model.embeddings.position_embedding.weight");
    if (!pos || pos->shape.size() != 2 || pos->shape[1] != (uint32_t)g_weights.dim) {
        std::cerr << "Missing or bad position_embedding\n";
        return false;
    }
    g_weights.pos_embedding = pos->data;

    // transformer blocks
    g_model.num_layers = 12; // or 24 depending on your CLIP variant
    g_model.blocks.resize(g_model.num_layers);

    for (int l = 0; l < g_model.num_layers; ++l) {
        auto& b = g_model.blocks[l];
        int D = g_weights.dim;

        // layernorm 1
        {
            std::string ln1_w = "text_model.encoder.layers." + std::to_string(l) + ".layernorm1.weight";
            std::string ln1_b = "text_model.encoder.layers." + std::to_string(l) + ".layernorm1.bias";
            const WeightTensor* g = find_tensor(tensors, ln1_w);
            const WeightTensor* bt = find_tensor(tensors, ln1_b);
            if (!g || !bt || g->shape[0] != (uint32_t)D || bt->shape[0] != (uint32_t)D) {
                std::cerr << "Missing ln1 for layer " << l << "\n";
                return false;
            }
            b.ln1_gamma = g->data;
            b.ln1_beta  = bt->data;
        }

        // layernorm 2
        {
            std::string ln2_w = "text_model.encoder.layers." + std::to_string(l) + ".layernorm2.weight";
            std::string ln2_b = "text_model.encoder.layers." + std::to_string(l) + ".layernorm2.bias";
            const WeightTensor* g = find_tensor(tensors, ln2_w);
            const WeightTensor* bt = find_tensor(tensors, ln2_b);
            if (!g || !bt || g->shape[0] != (uint32_t)D || bt->shape[0] != (uint32_t)D) {
                std::cerr << "Missing ln2 for layer " << l << "\n";
                return false;
            }
            b.ln2_gamma = g->data;
            b.ln2_beta  = bt->data;
        }

        // attention weights
        {
            std::string wq_n = "text_model.encoder.layers." + std::to_string(l) + ".self_attn.q_proj.weight";
            std::string wk_n = "text_model.encoder.layers." + std::to_string(l) + ".self_attn.k_proj.weight";
            std::string wv_n = "text_model.encoder.layers." + std::to_string(l) + ".self_attn.v_proj.weight";
            std::string wo_n = "text_model.encoder.layers." + std::to_string(l) + ".self_attn.out_proj.weight";

            const WeightTensor* wq = find_tensor(tensors, wq_n);
            const WeightTensor* wk = find_tensor(tensors, wk_n);
            const WeightTensor* wv = find_tensor(tensors, wv_n);
            const WeightTensor* wo = find_tensor(tensors, wo_n);

            if (!wq || !wk || !wv || !wo ||
                wq->shape.size() != 2 || wk->shape.size() != 2 ||
                wv->shape.size() != 2 || wo->shape.size() != 2 ||
                wq->shape[0] != (uint32_t)D || wq->shape[1] != (uint32_t)D ||
                wk->shape[0] != (uint32_t)D || wk->shape[1] != (uint32_t)D ||
                wv->shape[0] != (uint32_t)D || wv->shape[1] != (uint32_t)D ||
                wo->shape[0] != (uint32_t)D || wo->shape[1] != (uint32_t)D) {
                std::cerr << "Missing attn weights for layer " << l << "\n";
                return false;
            }

            b.attn_wq = wq->data;
            b.attn_wk = wk->data;
            b.attn_wv = wv->data;
            b.attn_wo = wo->data;
        }

        // MLP weights
        {
            std::string w1_n = "text_model.encoder.layers." + std::to_string(l) + ".mlp.fc1.weight";
            std::string w2_n = "text_model.encoder.layers." + std::to_string(l) + ".mlp.fc2.weight";

            const WeightTensor* w1 = find_tensor(tensors, w1_n);
            const WeightTensor* w2 = find_tensor(tensors, w2_n);

            if (!w1 || !w2 || w1->shape.size() != 2 || w2->shape.size() != 2 ||
                w1->shape[1] != (uint32_t)D || w2->shape[0] != (uint32_t)D) {
                std::cerr << "Missing MLP weights for layer " << l << "\n";
                return false;
            }

            b.mlp_w1 = w1->data; // [D, M]
            b.mlp_w2 = w2->data; // [M, D]
        }
    }

    return true;
}

void sd_clip_free() {
    // no-op
}

// ------------------------------------------------------------
// Encode
// ------------------------------------------------------------
std::vector<float> sd_clip_encode(const std::string& text) {
    std::vector<int> tokens = sd_clip_tokenize(text);

    const int dim     = g_weights.dim;
    const int max_len = 77;
    const int T       = max_len;

    // pad/truncate tokens to max_len
    tokens.resize(max_len, 0);

    // sequence [T, D]
    std::vector<float> seq((size_t)T * dim, 0.0f);

    // 1) token + positional embeddings
    for (int t = 0; t < T; ++t) {
        int id = tokens[t];
        if (id < 0 || id >= g_weights.vocab_size) id = 0;

        const float* tok_emb = &g_weights.token_embedding[(size_t)id * dim];
        const float* pos_emb = &g_weights.pos_embedding[(size_t)t * dim];
        float* dst = &seq[(size_t)t * dim];

        for (int i = 0; i < dim; ++i) {
            dst[i] = tok_emb[i] + pos_emb[i];
        }
    }

    // 2) transformer blocks
    for (int layer = 0; layer < g_model.num_layers; ++layer) {
        const ClipTransformerBlock& b = g_model.blocks[layer];

        // LN1
        for (int t = 0; t < T; ++t) {
            float* token = &seq[(size_t)t * dim];
            clip_layernorm_vec(token, dim, b.ln1_gamma, b.ln1_beta);
        }

        // self-attention + residual
        clip_attention(seq, T, dim, b);

        // LN2
        for (int t = 0; t < T; ++t) {
            float* token = &seq[(size_t)t * dim];
            clip_layernorm_vec(token, dim, b.ln2_gamma, b.ln2_beta);
        }

        // MLP + residual
        clip_mlp(seq, T, dim, b);
    }

    // 3) pool (CLS or mean). Here: mean pool
    std::vector<float> out(dim, 0.0f);
    for (int t = 0; t < T; ++t) {
        const float* src = &seq[(size_t)t * dim];
        for (int i = 0; i < dim; ++i) {
            out[i] += src[i];
        }
    }
    for (int i = 0; i < dim; ++i) {
        out[i] /= (float)T;
    }

    return out;
}

// ------------------------------------------------------------
// Tokenizer wrapper
// ------------------------------------------------------------
std::vector<int> sd_clip_tokenize(const std::string& text) {
    return clip_bpe_tokenize(g_tok, text);
}
