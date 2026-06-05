#include "m3u8_parser.h"
#include <sstream>
#include <algorithm>
#include <regex>

std::vector<StreamVariant> M3u8Parser::parse(const std::string& content) {
    std::vector<StreamVariant> variants;
    std::istringstream stream(content);
    std::string line;
    StreamVariant current;
    bool expectUrl = false;

    while (std::getline(stream, line)) {
        if (line.empty()) continue;
        // Remove \r
        if (!line.empty() && line.back() == '\r') line.pop_back();

        if (line.rfind("#EXT-X-STREAM-INF:", 0) == 0) {
            current = StreamVariant{};
            expectUrl = true;
            // Parse BANDWIDTH
            std::regex bwRe("BANDWIDTH=(\\d+)");
            std::smatch m;
            if (std::regex_search(line, m, bwRe)) current.bandwidth = std::stoi(m[1]);
            // Parse RESOLUTION
            std::regex resRe("RESOLUTION=(\\d+)x(\\d+)");
            if (std::regex_search(line, m, resRe)) {
                current.width = std::stoi(m[1]);
                current.height = std::stoi(m[2]);
            }
            // Parse CODECS
            std::regex codecRe("CODECS=\"([^\"]+)\"");
            if (std::regex_search(line, m, codecRe)) current.codecs = m[1];
        } else if (expectUrl && line[0] != '#') {
            current.url = line;
            variants.push_back(current);
            expectUrl = false;
        }
    }

    // If no variants (direct m3u8 segments), return content itself
    if (variants.empty() && content.find("#EXTINF") != std::string::npos) {
        variants.push_back({"__self__", 0, 0, 0, ""});
    }

    return variants;
}

std::string M3u8Parser::selectBestQuality(const std::vector<StreamVariant>& variants) {
    if (variants.empty()) return "";
    if (variants.size() == 1) return variants[0].url;

    // Sort by bandwidth descending, pick highest under 1080p first
    auto it = std::max_element(variants.begin(), variants.end(),
        [](const StreamVariant& a, const StreamVariant& b) {
            // Prefer 1080p or best available
            if (a.height <= 1080 && b.height > 1080) return false;
            if (a.height > 1080 && b.height <= 1080) return true;
            return a.bandwidth < b.bandwidth;
        });
    return it->url;
}
