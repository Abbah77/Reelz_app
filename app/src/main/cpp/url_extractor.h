#pragma once
#include <string>
#include <vector>

class UrlExtractor {
public:
    // Extract first .m3u8 URL found in HTML or JS content
    std::string extractM3u8(const std::string& content);
    // Extract all .m3u8 URLs
    std::vector<std::string> extractAllM3u8(const std::string& content);
};
