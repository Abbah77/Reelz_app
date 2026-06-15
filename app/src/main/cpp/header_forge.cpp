#include "header_forge.h"
#include <string>
#include <unordered_map>
#include <vector>
#include <random>
#include <sstream>

/**
 * Forge HTTP request headers that look like a real Chrome browser.
 * Helps bypass naive bot-detection on embed sources.
 */

// Chrome versions to rotate through
static const std::vector<std::string> CHROME_VERSIONS = {
    "120.0.0.0", "121.0.0.0", "122.0.0.0", "123.0.0.0", "124.0.0.0"
};

// Android device strings
static const std::vector<std::string> ANDROID_DEVICES = {
    "Pixel 8",
    "Pixel 8 Pro",
    "Samsung Galaxy S24",
    "OnePlus 12",
    "Xiaomi 14",
};

static inline int randomInt(int min, int max) {
    static std::mt19937 rng(std::random_device{}());
    std::uniform_int_distribution<int> dist(min, max);
    return dist(rng);
}

static inline std::string randomPick(const std::vector<std::string>& v) {
    return v[randomInt(0, static_cast<int>(v.size()) - 1)];
}

std::string buildUserAgent(bool mobile) {
    const auto& chrome  = randomPick(CHROME_VERSIONS);
    if (mobile) {
        const auto& device = randomPick(ANDROID_DEVICES);
        return "Mozilla/5.0 (Linux; Android 14; " + device + ") "
               "AppleWebKit/537.36 (KHTML, like Gecko) "
               "Chrome/" + chrome + " Mobile Safari/537.36";
    } else {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
               "AppleWebKit/537.36 (KHTML, like Gecko) "
               "Chrome/" + chrome + " Safari/537.36";
    }
}

Headers forgeHeaders(
    const std::string& referer,
    const std::string& origin,
    bool               mobile,
    bool               isXhr
) {
    Headers h;
    h["User-Agent"]      = buildUserAgent(mobile);
    h["Accept-Language"] = "en-US,en;q=0.9";
    h["Accept-Encoding"] = "gzip, deflate, br";
    h["Connection"]      = "keep-alive";
    h["Sec-Fetch-Site"]  = "cross-site";
    h["Sec-Fetch-Mode"]  = isXhr ? "cors" : "navigate";
    h["Sec-Fetch-Dest"]  = isXhr ? "empty" : "iframe";

    if (!referer.empty()) h["Referer"]  = referer;
    if (!origin.empty())  h["Origin"]   = origin;

    if (isXhr) {
        h["Accept"] = "application/json, text/plain, */*";
        h["X-Requested-With"] = "XMLHttpRequest";
    } else {
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,"
                      "image/avif,image/webp,image/apng,*/*;q=0.8";
    }

    // Randomised sec-ch-ua to match chrome version
    const auto& cv = h["User-Agent"];
    size_t vStart  = cv.find("Chrome/");
    if (vStart != std::string::npos) {
        vStart += 7;
        std::string major = cv.substr(vStart, cv.find('.', vStart) - vStart);
        h["Sec-CH-UA"] = "\"Google Chrome\";v=\"" + major + "\", "
                         "\"Chromium\";v=\"" + major + "\", "
                         "\"Not=A?Brand\";v=\"99\"";
        h["Sec-CH-UA-Mobile"]   = mobile ? "?1" : "?0";
        h["Sec-CH-UA-Platform"] = mobile ? "\"Android\"" : "\"Windows\"";
    }

    return h;
}

// Serialise headers map to a flat "Key: Value\r\n" string for raw socket usage
std::string serialiseHeaders(const Headers& h) {
    std::ostringstream oss;
    for (const auto& [k, v] : h) {
        oss << k << ": " << v << "\r\n";
    }
    return oss.str();
}
