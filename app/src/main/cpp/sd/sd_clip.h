#pragma once
#include <string>
#include <vector>

// ------------------------------------------------------------
// 1. Define ClipWeights FIRST
// ------------------------------------------------------------
struct ClipWeights {
    std::vector<float> token_embedding;   // [vocab_size * dim]
    std::vector<float> pos_embedding;     // [77 * dim]
    int vocab_size = 0;
    int dim = 768;
};

// ------------------------------------------------------------
// 2. Define Transformer Block structs
// ------------------------------------------------------------
struct ClipTransformerBlock {
    std::vector<float> ln1_gamma, ln1_beta;
    std::vector<float> ln2_gamma, ln2_beta;

    std::vector<float> attn_wq, attn_wk, attn_wv, attn_wo;
    std::vector<float> mlp_w1, mlp_w2;
};

// ------------------------------------------------------------
// 3. Define ClipModel AFTER the above
// ------------------------------------------------------------
struct ClipModel {
    ClipWeights weights;
    std::vector<ClipTransformerBlock> blocks;
    int num_layers = 24;
};

// ------------------------------------------------------------
// 4. Public CLIP API
// ------------------------------------------------------------
bool sd_clip_init(const std::string& model_dir);
void sd_clip_free();

std::vector<int> sd_clip_tokenize(const std::string& text);
std::vector<float> sd_clip_encode(const std::string& text);

const ClipModel& sd_clip_get_model();
