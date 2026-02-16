#pragma once
#include <string>
#include <vector>
#include <cstdint>

// ============================================================================
// A single tensor loaded from a .bin weight file
// ============================================================================
//
// name  : full tensor name (e.g. "decoder.conv_in.weight")
// shape : list of dimensions (e.g. [320, 4, 3, 3])
// data  : float32 values in row-major order
//
// This matches the custom binary format:
//   uint32 name_len
//   char[name_len] name
//   uint32 ndims
//   uint32 dims[ndims]
//   uint32 dtype   (0 = float32)
//   float32 data[product(dims)]
// ============================================================================

struct WeightTensor {
    std::string name;
    std::vector<uint32_t> shape;
    std::vector<float> data;
};

// ============================================================================
// Load all tensors from a weight file
// ============================================================================
//
// Returns a vector of WeightTensor objects. If the file is missing or corrupted,
// the returned vector may be empty.
//
// This function is implemented in sd_weight_loader.cpp.
// ============================================================================
std::vector<WeightTensor> load_weight_file(const std::string& path);
