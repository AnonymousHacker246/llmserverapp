#pragma once
#include <string>
#include <vector>
#include <unordered_map>

// ============================================================================
// CLIP Weights
// ============================================================================

struct ClipWeights {
    // Embeddings
    std::vector<float> token_embedding;   // [vocab_size * dim]
    std::vector<float> pos_embedding;     // [max_len * dim]

    int vocab_size = 0;
    int dim = 768;        // CLIP text encoder hidden size
    int max_len = 77;     // Stable Diffusion uses 77 tokens
};

// ============================================================================
// Transformer Block
// ============================================================================

struct ClipTransformerBlock {
    // LayerNorm 1
    std::vector<float> ln1_gamma;
    std::vector<float> ln1_beta;

    // Attention weights (single-head or multi-head flattened)
    std::vector<float> attn_wq;   // [dim * dim]
    std::vector<float> attn_wk;   // [dim * dim]
    std::vector<float> attn_wv;   // [dim * dim]
    std::vector<float> attn_wo;   // [dim * dim]

    // LayerNorm 2
    std::vector<float> ln2_gamma;
    std::vector<float> ln2_beta;

    // MLP weights
    std::vector<float> mlp_w1;    // [dim * hidden_dim]
    std::vector<float> mlp_w2;    // [hidden_dim * dim]
};

// ============================================================================
// CLIP Model
// ============================================================================

struct ClipModel {
    ClipWeights weights;
    std::vector<ClipTransformerBlock> blocks;
    int num_layers = 12;   // SD1.5 uses CLIP ViT-L/14 (12 layers)
};

// ============================================================================
// Public API
// ============================================================================

bool sd_clip_init(const std::string& model_dir);
void sd_clip_free();

std::vector<int>   sd_clip_tokenize(const std::string& text);
std::vector<float> sd_clip_encode(const std::string& text);

const ClipModel&   sd_clip_get_model();
const ClipWeights& sd_clip_get_weights();
