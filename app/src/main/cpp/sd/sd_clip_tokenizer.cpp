#include "sd_clip_tokenizer.h"
#include <fstream>
#include <sstream>
#include <unordered_map>
#include <vector>
#include <string>
#include <limits>
#include <cctype>
#include <iostream>

// -----------------------------------------------------------------------------
// ClipTokenizer expected layout (in sd_clip_tokenizer.h)
// -----------------------------------------------------------------------------
/*
struct ClipTokenizer {
    std::unordered_map<std::string, int> token_to_id;
    std::unordered_map<std::string, int> bpe_ranks; // "a b" -> rank
    int bos_id = 0;
    int eos_id = 0;
    int pad_id = 0;
};
*/

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

static bool load_vocab(const std::string& path, ClipTokenizer& tok) {
    std::ifstream f(path);
    if (!f.is_open()) {
        std::cerr << "Failed to open vocab: " << path << "\n";
        return false;
    }

    std::string line;
    while (std::getline(f, line)) {
        if (line.empty()) continue;
        std::istringstream iss(line);
        std::string token;
        int id;
        if (!(iss >> token >> id)) continue;
        tok.token_to_id[token] = id;
    }

    // Set some common special tokens if present
    auto set_if = [&](const std::string& t, int& dst) {
        auto it = tok.token_to_id.find(t);
        if (it != tok.token_to_id.end()) dst = it->second;
    };

    set_if("<|startoftext|>", tok.bos_id);
    set_if("<|endoftext|>",   tok.eos_id);
    set_if("<|pad|>",         tok.pad_id);

    return true;
}

static bool load_merges(const std::string& path, ClipTokenizer& tok) {
    std::ifstream f(path);
    if (!f.is_open()) {
        std::cerr << "Failed to open merges: " << path << "\n";
        return false;
    }

    std::string line;
    int rank = 0;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream iss(line);
        std::string a, b;
        if (!(iss >> a >> b)) continue;
        std::string key = a + " " + b;
        tok.bpe_ranks[key] = rank++;
    }
    return true;
}

static std::vector<std::string> word_to_chars(const std::string& word) {
    std::vector<std::string> out;
    for (unsigned char c : word) {
        out.push_back(std::string(1, (char)c));
    }
    return out;
}

static std::pair<int, int> find_best_pair(
        const std::vector<std::string>& tokens,
        const std::unordered_map<std::string, int>& bpe_ranks
) {
    int best_rank = std::numeric_limits<int>::max();
    int best_pos  = -1;

    for (int i = 0; i + 1 < (int)tokens.size(); ++i) {
        std::string key = tokens[i] + " " + tokens[i + 1];
        auto it = bpe_ranks.find(key);
        if (it != bpe_ranks.end() && it->second < best_rank) {
            best_rank = it->second;
            best_pos  = i;
        }
    }
    return {best_pos, best_rank};
}

static std::vector<std::string> bpe(
        const std::string& word,
        const ClipTokenizer& tok
) {
    if (word.empty()) return {};

    std::vector<std::string> tokens = word_to_chars(word);

    while (true) {
        auto [pos, rank] = find_best_pair(tokens, tok.bpe_ranks);
        if (pos == -1) break;

        // merge tokens[pos] and tokens[pos+1]
        std::string merged = tokens[pos] + tokens[pos + 1];
        std::vector<std::string> new_tokens;
        new_tokens.reserve(tokens.size() - 1);

        for (int i = 0; i < (int)tokens.size(); ++i) {
            if (i == pos) {
                new_tokens.push_back(merged);
                ++i; // skip next
            } else {
                new_tokens.push_back(tokens[i]);
            }
        }
        tokens.swap(new_tokens);
    }

    return tokens;
}

// -----------------------------------------------------------------------------
// Public API
// -----------------------------------------------------------------------------

bool load_clip_tokenizer(const std::string& model_dir, ClipTokenizer& tok) {
    std::string vocab_path  = model_dir + "/vocab.txt";
    std::string merges_path = model_dir + "/merges.txt";

    if (!load_vocab(vocab_path, tok))  return false;
    if (!load_merges(merges_path, tok)) return false;

    if (tok.pad_id == 0) {
        // fallback: if no explicit pad, use eos
        tok.pad_id = tok.eos_id;
    }

    return true;
}

std::vector<int> clip_bpe_tokenize(const ClipTokenizer& tok, const std::string& text) {
    std::vector<int> tokens;

    // optional BOS
    if (tok.bos_id != 0) tokens.push_back(tok.bos_id);

    std::istringstream iss(text);
    std::string word;

    while (iss >> word) {
        // lowercase for CLIP-like behavior
        for (char& c : word) c = (char)std::tolower((unsigned char)c);

        std::vector<std::string> bpe_tokens = bpe(word, tok);
        for (auto& bt : bpe_tokens) {
            auto it = tok.token_to_id.find(bt);
            if (it != tok.token_to_id.end()) {
                tokens.push_back(it->second);
            } else {
                // unknown token → use pad or 0
                tokens.push_back(tok.pad_id);
            }
        }
    }

    // optional EOS
    if (tok.eos_id != 0) tokens.push_back(tok.eos_id);

    // pad/truncate to 77
    const int max_len = 77;
    if ((int)tokens.size() > max_len) {
        tokens.resize(max_len);
    } else {
        while ((int)tokens.size() < max_len) {
            tokens.push_back(tok.pad_id);
        }
    }

    return tokens;
}
