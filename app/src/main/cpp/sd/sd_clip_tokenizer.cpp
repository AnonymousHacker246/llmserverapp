#include "sd_clip_tokenizer.h"
#include <sstream>

// Minimal stub tokenizer: no vocab, no merges.
// Just splits on spaces and assigns fake token IDs.

bool load_clip_tokenizer(const std::string& model_dir, ClipTokenizer& tok) {
    // No vocab to load yet
    return true;
}

std::vector<int> clip_bpe_tokenize(const ClipTokenizer& tok, const std::string& text) {
    std::vector<int> tokens;

    std::istringstream iss(text);
    std::string word;

    while (iss >> word) {
        // Fake token ID: hash the word
        size_t h = std::hash<std::string>{}(word);
        tokens.push_back(int(h & 0x7FFF)); // keep it small
    }

    // Pad/truncate to 77 tokens
    const int max_len = 77;
    if (tokens.size() > max_len) {
        tokens.resize(max_len);
    } else {
        while (tokens.size() < max_len) {
            tokens.push_back(0); // pad token
        }
    }

    return tokens;
}
