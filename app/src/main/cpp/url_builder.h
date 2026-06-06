#pragma once
#include <string>

namespace reelz {
    std::string buildEmbedUrl(
        const std::string& base,
        int tmdbId,
        int mediaType,
        int season,
        int episode
    );
}
