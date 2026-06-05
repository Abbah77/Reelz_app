#pragma once
#include <string>
#include <vector>

struct StreamVariant {
    std::string url;
    int bandwidth = 0;
    int width = 0;
    int height = 0;
    std::string codecs;
};

class M3u8Parser {
public:
    std::vector<StreamVariant> parse(const std::string& content);
    std::string selectBestQuality(const std::vector<StreamVariant>& variants);
};
