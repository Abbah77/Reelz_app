#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <algorithm>

struct HlsSegment {
    std::string url;
    double      duration  = 0.0;
    int64_t     byteStart = -1;
    int64_t     byteLen   = -1;
};

struct HlsVariant {
    std::string url;
    int         bandwidth  = 0;
    int         width      = 0;
    int         height     = 0;
    std::string codecs;
    std::string resolution;
};

struct HlsSubtitle {
    std::string url;
    std::string language;
    std::string name;
};

struct HlsPlaylist {
    bool                     isMaster       = false;
    std::vector<HlsSegment>  segments;
    std::vector<HlsVariant>  variants;
    std::vector<HlsSubtitle> subtitles;
    double                   targetDuration = 0.0;
    bool                     isEndList      = false;
    std::string              baseUrl;

    const HlsVariant* bestVariant() const {
        if (variants.empty()) return nullptr;
        auto it = std::max_element(variants.begin(), variants.end(),
            [](const HlsVariant& a, const HlsVariant& b){ return a.bandwidth < b.bandwidth; });
        return &(*it);
    }

    const HlsVariant* variantForHeight(int targetH) const {
        if (variants.empty()) return nullptr;
        const HlsVariant* best = nullptr;
        int bestDiff = INT32_MAX;
        for (const auto& v : variants) {
            int diff = abs(v.height - targetH);
            if (diff < bestDiff) { bestDiff = diff; best = &v; }
        }
        return best;
    }
};

HlsPlaylist parseM3u8(const std::string& content, const std::string& baseUrl);
