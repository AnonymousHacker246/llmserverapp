#include "sd_weight_loader.h"
#include <fstream>
#include <iostream>

std::vector<WeightTensor> load_weight_file(const std::string& path) {
    std::vector<WeightTensor> tensors;

    std::ifstream f(path, std::ios::binary);
    if (!f.is_open()) {
        std::cerr << "Failed to open weight file: " << path << "\n";
        return tensors;
    }

    while (true) {
        // ------------------------------------------------------------
        // 1. Read name length
        // ------------------------------------------------------------
        uint32_t name_len = 0;
        if (!f.read(reinterpret_cast<char*>(&name_len), sizeof(name_len))) {
            // Clean EOF → stop normally
            break;
        }

        if (name_len == 0 || name_len > 10'000) {
            std::cerr << "Invalid tensor name length: " << name_len << "\n";
            break;
        }

        // ------------------------------------------------------------
        // 2. Read name
        // ------------------------------------------------------------
        std::string name(name_len, '\0');
        if (!f.read(&name[0], name_len)) {
            std::cerr << "Unexpected EOF while reading tensor name\n";
            break;
        }

        // ------------------------------------------------------------
        // 3. Read number of dims
        // ------------------------------------------------------------
        uint32_t ndims = 0;
        if (!f.read(reinterpret_cast<char*>(&ndims), sizeof(ndims))) {
            std::cerr << "Unexpected EOF while reading ndims for " << name << "\n";
            break;
        }

        if (ndims == 0 || ndims > 8) {
            std::cerr << "Invalid ndims (" << ndims << ") for tensor " << name << "\n";
            break;
        }

        // ------------------------------------------------------------
        // 4. Read dims
        // ------------------------------------------------------------
        std::vector<uint32_t> shape(ndims);
        for (uint32_t i = 0; i < ndims; ++i) {
            if (!f.read(reinterpret_cast<char*>(&shape[i]), sizeof(uint32_t))) {
                std::cerr << "Unexpected EOF while reading shape for " << name << "\n";
                break;
            }
        }

        // ------------------------------------------------------------
        // 5. Read dtype
        // ------------------------------------------------------------
        uint32_t dtype = 0;
        if (!f.read(reinterpret_cast<char*>(&dtype), sizeof(dtype))) {
            std::cerr << "Unexpected EOF while reading dtype for " << name << "\n";
            break;
        }

        if (dtype != 0) {
            std::cerr << "Unsupported dtype (" << dtype << ") in tensor " << name << "\n";
            break;
        }

        // ------------------------------------------------------------
        // 6. Compute number of elements
        // ------------------------------------------------------------
        size_t count = 1;
        for (uint32_t d : shape) {
            if (d == 0) {
                std::cerr << "Invalid zero dimension in tensor " << name << "\n";
                break;
            }
            count *= d;
        }

        // ------------------------------------------------------------
        // 7. Read float32 data
        // ------------------------------------------------------------
        std::vector<float> data(count);
        size_t bytes = count * sizeof(float);

        if (!f.read(reinterpret_cast<char*>(data.data()), bytes)) {
            std::cerr << "Unexpected EOF while reading data for " << name << "\n";
            break;
        }

        // ------------------------------------------------------------
        // 8. Store tensor
        // ------------------------------------------------------------
        tensors.push_back({ name, shape, std::move(data) });
    }

    return tensors;
}
