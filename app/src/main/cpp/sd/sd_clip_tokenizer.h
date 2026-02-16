#pragma once
#include <string>
#include <unordered_map>
#include <vector>

// ============================================================================
// Real CLIP Tokenizer (BPE)
// ============================================================================
//
// This structure supports:
//   - vocab: token string -> token ID
//   - bpe_ranks: "a b" -> merge rank
//   - special tokens: BOS, EOS, PAD
//
// The implementation in sd_clip_tokenizer.cpp performs:
//   - loading vocab.txt
//   - loading merges.txt
//   - byte-pair encoding (BPE)
//   - BOS/EOS insertion
//   - padding/truncation to 77 tokens
// ============================================================================

struct ClipTokenizer {
    // Vocabulary: token -> ID
    std::unordered_map<std::string, int> token_to_id;

    // Merge ranks: "a b" -> rank
    std::unordered_map<std::string, int> bpe_ranks;

    // Special token IDs
    int bos_id = 0;   // <|startoftext|>
    int eos_id = 0;   // <|endoftext|>
    int pad_id = 0;   // <|pad|>
};

// Load vocab + merges from model_dir
bool load_clip_tokenizer(const std::string& model_dir, ClipTokenizer& tok);

// Tokenize text using real BPE
std::vector<int> clip_bpe_tokenize(const ClipTokenizer& tok, const std::string& text);
