#include "url_extractor.h"
#include <regex>
#include <algorithm>

std::string UrlExtractor::extractM3u8(const std::string& content) {
    auto all = extractAllM3u8(content);
    return all.empty() ? "" : all[0];
}

std::vector<std::string> UrlExtractor::extractAllM3u8(const std::string& content) {
    std::vector<std::string> results;
    // Match http(s) URLs ending in .m3u8 (with optional query params)
    std::regex m3u8Re(R"((https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*))");
    auto begin = std::sregex_iterator(content.begin(), content.end(), m3u8Re);
    auto end = std::sregex_iterator();
    for (auto it = begin; it != end; ++it) {
        std::string url = (*it)[1].str();
        // Decode common JS escapes
        std::string decoded;
        for (size_t i = 0; i < url.size(); ++i) {
            if (url[i] == '\\' && i + 1 < url.size()) {
                if (url[i+1] == 'n' || url[i+1] == 'r') { ++i; continue; }
                if (url[i+1] == '/') { decoded += '/'; ++i; continue; }
            }
            decoded += url[i];
        }
        results.push_back(decoded);
    }
    // Deduplicate
    std::sort(results.begin(), results.end());
    results.erase(std::unique(results.begin(), results.end()), results.end());
    return results;
}
