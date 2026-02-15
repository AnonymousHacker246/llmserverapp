#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct WeightTensor {
    std::string name;
    std::vector<uint32_t> shape;
    std::vector<float> data;
};

std::vector<WeightTensor> load_weight_file(const std::string& path);
