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
        // 1. Read name length
        uint32_t name_len = 0;
        if (!f.read(reinterpret_cast<char*>(&name_len), sizeof(name_len)))
            break; // EOF

        // 2. Read name
        std::string name(name_len, '\0');
        f.read(&name[0], name_len);

        // 3. Read number of dims
        uint32_t ndims = 0;
        f.read(reinterpret_cast<char*>(&ndims), sizeof(ndims));

        // 4. Read dims
        std::vector<uint32_t> shape(ndims);
        for (uint32_t i = 0; i < ndims; ++i) {
            f.read(reinterpret_cast<char*>(&shape[i]), sizeof(uint32_t));
        }

        // 5. Read dtype (0 = float32)
        uint32_t dtype = 0;
        f.read(reinterpret_cast<char*>(&dtype), sizeof(dtype));

        if (dtype != 0) {
            std::cerr << "Unsupported dtype in " << name << "\n";
            return tensors;
        }

        // 6. Compute number of elements
        size_t count = 1;
        for (uint32_t d : shape) count *= d;

        // 7. Read float32 data
        std::vector<float> data(count);
        f.read(reinterpret_cast<char*>(data.data()), count * sizeof(float));

        // 8. Store tensor
        tensors.push_back({ name, shape, std::move(data) });
    }

    return tensors;
}
