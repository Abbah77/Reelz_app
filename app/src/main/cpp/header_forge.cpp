#include "header_forge.h"
#include <sstream>

namespace reelz {

std::string forgeHeaders(
    const std::string& referer,
    const std::string& origin,
    const std::string& userAgent)
{
    // Returns a simple pipe-delimited header string for JNI transport
    // Format: "Referer|<val>||Origin|<val>||User-Agent|<val>"
    std::ostringstream ss;
    if (!referer.empty())   ss << "Referer|"    << referer   << "||";
    if (!origin.empty())    ss << "Origin|"     << origin    << "||";
    if (!userAgent.empty()) ss << "User-Agent|" << userAgent << "||";
    std::string result = ss.str();
    // Trim trailing "||"
    if (result.size() >= 2 && result.substr(result.size() - 2) == "||")
        result.erase(result.size() - 2);
    return result;
}

} // namespace reelz
