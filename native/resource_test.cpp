#include "utils/utils.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <functional>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>

using libretrodroid::Utils;
static void rejects(const std::function<void()>& action) {
    bool rejected = false;
    try { action(); } catch (const std::exception&) { rejected = true; }
    assert(rejected);
}
int main() {
    char folder[] = "/tmp/retrotv-resource-XXXXXX";
    assert(mkdtemp(folder));
    const std::string path = std::string(folder) + "/rom.bin";
    { std::ofstream out(path, std::ios::binary); out << std::string(1024 * 1024, 'R'); }
    for (int i = 0; i < 1000; i++) {
        auto rom = Utils::readFileAsBytes(path);
        assert(rom.size == 1024 * 1024 && rom.data[rom.size - 1] == 'R');
        // Transferred ownership survives the temporary read result, then releases once.
        auto ownedByGame = std::move(rom.data);
        assert(!rom.data && ownedByGame[0] == 'R');
        const int fd = open(path.c_str(), O_RDONLY);
        assert(fd >= 0);
        auto fromDescriptor = Utils::readFileAsBytes(fd);
        assert(fromDescriptor.size == rom.size);
        assert(fcntl(fd, F_GETFD) == -1); // FILE and descriptor are both closed.
    }
    rejects([&] { Utils::readFileAsBytes(path + ".missing"); });
    { std::ofstream out(path, std::ios::trunc); }
    rejects([&] { Utils::readFileAsBytes(path); });
    std::filesystem::resize_file(path, 33 * 1024 * 1024);
    rejects([&] { Utils::readFileAsBytes(path); });
    const int fd = open(path.c_str(), O_RDONLY);
    rejects([&] { Utils::readFileAsBytes(fd); });
    assert(fcntl(fd, F_GETFD) == -1);
    std::filesystem::remove_all(folder);
    std::cout << "1000 ROM ownership/descriptor cycles and invalid-size checks passed\n";
}
