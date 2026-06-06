#include "url_builder.h"
#include <sstream>

namespace reelz {

std::string buildEmbedUrl(
    const std::string& base,
    int tmdbId,
    int mediaType,   // 0=movie, 1=tv
    int season,
    int episode)
{
    std::ostringstream ss;
    ss << base;
    if (base.back() != '/') ss << '/';
    if (mediaType == 0) {
        ss << "movie/" << tmdbId;
    } else {
        ss << "tv/" << tmdbId << "/" << season << "/" << episode;
    }
    return ss.str();
}

} // namespace reelz
