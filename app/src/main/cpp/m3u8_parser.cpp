#include "m3u8_parser.h"
#include <sstream>
#include <vector>
#include <algorithm>
#include <regex>

namespace reelz {

struct Variant {
    long long bandwidth = 0;
    std::string uri;
};

bool isValidM3u8(const std::string& content) {
    return content.find("#EXTM3U") != std::string::npos;
}

/**
 * Parse HLS master playlist.
 * Picks variant with highest BANDWIDTH — that's the best quality stream.
 * If only media segments (#EXTINF) are found, it's already a media playlist
 * and we return empty to signal ExoPlayer should use the URL directly.
 */
std::string extractBestVariant(const std::string& content) {
    if (!isValidM3u8(content)) return "";

    // If it's a media playlist (not master), signal pass-through
    if (content.find("#EXTINF") != std::string::npos &&
        content.find("#EXT-X-STREAM-INF") == std::string::npos) {
        return "";  // caller uses original URL
    }

    std::vector<Variant> variants;
    std::istringstream stream(content);
    std::string line;

    while (std::getline(stream, line)) {
        if (line.rfind("#EXT-X-STREAM-INF", 0) == 0) {
            Variant v;
            // Extract BANDWIDTH=
            auto bwPos = line.find("BANDWIDTH=");
            if (bwPos != std::string::npos) {
                bwPos += 10;
                auto end = line.find(',', bwPos);
                std::string bwStr = line.substr(bwPos, end == std::string::npos ? end : end - bwPos);
                try { v.bandwidth = std::stoll(bwStr); } catch (...) {}
            }
            // Next non-empty line is the URI
            while (std::getline(stream, line)) {
                if (!line.empty() && line[0] != '#') {
                    v.uri = line;
                    break;
                }
            }
            if (!v.uri.empty()) variants.push_back(v);
        }
    }

    if (variants.empty()) return "";

    // Sort descending by bandwidth — highest quality first
    std::sort(variants.begin(), variants.end(), [](const Variant& a, const Variant& b) {
        return a.bandwidth > b.bandwidth;
    });

    return variants.front().uri;
}

} // namespace reelz
