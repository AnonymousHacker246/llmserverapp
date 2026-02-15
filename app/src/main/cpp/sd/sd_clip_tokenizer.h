#pragma once
#include <string>
#include <unordered_map>
#include <vector>

struct ClipTokenizer {
    std::unordered_map<std::string, int> vocab;
    std::vector<std::pair<std::string, std::string>> merges;
};

bool load_clip_tokenizer(const std::string& model_dir, ClipTokenizer& tok);
std::vector<int> clip_bpe_tokenize(const ClipTokenizer& tok, const std::string& text);
