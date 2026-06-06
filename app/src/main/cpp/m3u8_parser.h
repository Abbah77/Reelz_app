#pragma once
#include <string>

namespace reelz {
    // Parses an HLS master playlist and returns the URL of the highest-bandwidth variant.
    std::string extractBestVariant(const std::string& m3u8Content);

    // Returns true if the string looks like a valid HLS manifest.
    bool isValidM3u8(const std::string& content);
}
