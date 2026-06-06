#pragma once
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>

/**
 * Ultra-fast native HLS m3u8 parser.
 * Extracts segment URLs, bandwidth variants, and sub-playlists
 * in a single linear pass — no regex, no heap allocations per-segment.
 */

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
    bool                     isMaster   = false;
    std::vector<HlsSegment>  segments;
    std::vector<HlsVariant>  variants;
    std::vector<HlsSubtitle> subtitles;
    double                   targetDuration = 0.0;
    bool                     isEndList      = false;
    std::string              baseUrl;

    // Best variant by highest bandwidth
    const HlsVariant* bestVariant() const {
        if (variants.empty()) return nullptr;
        auto it = std::max_element(variants.begin(), variants.end(),
            [](const HlsVariant& a, const HlsVariant& b){ return a.bandwidth < b.bandwidth; });
        return &(*it);
    }

    // Variant matching requested height (or closest)
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

static inline std::string trim(const std::string& s) {
    size_t start = s.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    size_t end = s.find_last_not_of(" \t\r\n");
    return s.substr(start, end - start + 1);
}

static inline std::string resolveUrl(const std::string& base, const std::string& rel) {
    if (rel.find("http://") == 0 || rel.find("https://") == 0) return rel;
    if (rel.find("//") == 0) {
        size_t proto = base.find("://");
        return (proto != std::string::npos ? base.substr(0, proto) : "https") + ":" + rel;
    }
    if (rel[0] == '/') {
        size_t proto = base.find("://");
        if (proto == std::string::npos) return rel;
        size_t slash = base.find('/', proto + 3);
        return (slash != std::string::npos ? base.substr(0, slash) : base) + rel;
    }
    // relative
    size_t lastSlash = base.find_last_of('/');
    return (lastSlash != std::string::npos ? base.substr(0, lastSlash + 1) : base) + rel;
}

static inline int parseAttrInt(const std::string& line, const std::string& key) {
    size_t pos = line.find(key + "=");
    if (pos == std::string::npos) return 0;
    pos += key.size() + 1;
    size_t end = line.find_first_of(",\r\n", pos);
    try { return std::stoi(line.substr(pos, end - pos)); }
    catch (...) { return 0; }
}

static inline std::string parseAttrStr(const std::string& line, const std::string& key) {
    size_t pos = line.find(key + "=\"");
    if (pos == std::string::npos) return "";
    pos += key.size() + 2;
    size_t end = line.find('"', pos);
    return (end != std::string::npos) ? line.substr(pos, end - pos) : "";
}

HlsPlaylist parseM3u8(const std::string& content, const std::string& baseUrl) {
    HlsPlaylist playlist;
    playlist.baseUrl = baseUrl;

    std::istringstream stream(content);
    std::string        line;

    HlsVariant  pendingVariant;
    HlsSegment  pendingSegment;
    bool        hasPendingVariant = false;
    bool        hasPendingSegment = false;

    while (std::getline(stream, line)) {
        line = trim(line);
        if (line.empty()) continue;

        if (line[0] == '#') {
            // ── EXT-X-STREAM-INF → master playlist variant ──────────────
            if (line.find("#EXT-X-STREAM-INF:") == 0) {
                playlist.isMaster = true;
                pendingVariant = {};
                pendingVariant.bandwidth  = parseAttrInt(line, "BANDWIDTH");
                pendingVariant.width      = parseAttrInt(line, "RESOLUTION") != 0 ? 0 : 0;  // parsed below
                pendingVariant.codecs     = parseAttrStr(line, "CODECS");
                pendingVariant.resolution = parseAttrStr(line, "RESOLUTION");
                // Parse WxH from RESOLUTION=1920x1080
                if (!pendingVariant.resolution.empty()) {
                    size_t x = pendingVariant.resolution.find('x');
                    if (x != std::string::npos) {
                        try {
                            pendingVariant.width  = std::stoi(pendingVariant.resolution.substr(0, x));
                            pendingVariant.height = std::stoi(pendingVariant.resolution.substr(x + 1));
                        } catch (...) {}
                    }
                }
                hasPendingVariant = true;
                continue;
            }

            // ── EXT-X-MEDIA (subtitles / audio) ──────────────────────────
            if (line.find("#EXT-X-MEDIA:") == 0) {
                std::string type = parseAttrStr(line, "TYPE");
                if (type == "SUBTITLES" || type == "CLOSED-CAPTIONS") {
                    HlsSubtitle sub;
                    sub.language = parseAttrStr(line, "LANGUAGE");
                    sub.name     = parseAttrStr(line, "NAME");
                    sub.url      = resolveUrl(baseUrl, parseAttrStr(line, "URI"));
                    if (!sub.url.empty()) playlist.subtitles.push_back(sub);
                }
                continue;
            }

            // ── EXTINF → segment ─────────────────────────────────────────
            if (line.find("#EXTINF:") == 0) {
                pendingSegment = {};
                size_t comma = line.find(',', 8);
                std::string durStr = line.substr(8, comma != std::string::npos ? comma - 8 : std::string::npos);
                try { pendingSegment.duration = std::stod(durStr); } catch (...) {}
                hasPendingSegment = true;
                continue;
            }

            // ── EXT-X-TARGETDURATION ──────────────────────────────────────
            if (line.find("#EXT-X-TARGETDURATION:") == 0) {
                try { playlist.targetDuration = std::stod(line.substr(22)); } catch (...) {}
                continue;
            }

            // ── EXT-X-ENDLIST ─────────────────────────────────────────────
            if (line == "#EXT-X-ENDLIST") {
                playlist.isEndList = true;
                continue;
            }

            // ── EXT-X-BYTERANGE ──────────────────────────────────────────
            if (line.find("#EXT-X-BYTERANGE:") == 0 && hasPendingSegment) {
                std::string range = line.substr(17);
                size_t at = range.find('@');
                try {
                    pendingSegment.byteLen   = std::stoll(range.substr(0, at));
                    if (at != std::string::npos)
                        pendingSegment.byteStart = std::stoll(range.substr(at + 1));
                } catch (...) {}
                continue;
            }

        } else {
            // Non-comment line → URL
            if (hasPendingVariant) {
                pendingVariant.url = resolveUrl(baseUrl, line);
                playlist.variants.push_back(pendingVariant);
                hasPendingVariant = false;
            } else if (hasPendingSegment) {
                pendingSegment.url = resolveUrl(baseUrl, line);
                playlist.segments.push_back(pendingSegment);
                hasPendingSegment = false;
            }
        }
    }

    return playlist;
}
