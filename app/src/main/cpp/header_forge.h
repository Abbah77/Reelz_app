#pragma once
#include <string>

namespace reelz {
    // Returns a JSON-style key:value string of headers for logging/debugging.
    std::string forgeHeaders(
        const std::string& referer,
        const std::string& origin,
        const std::string& userAgent
    );
}
