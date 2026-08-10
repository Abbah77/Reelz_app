#pragma once
#include <string>
#include <unordered_map>

using Headers = std::unordered_map<std::string, std::string>;

std::string buildUserAgent(bool mobile = true);
Headers forgeHeaders(const std::string& referer, const std::string& origin,
                     bool mobile = true, bool isXhr = false);
std::string serialiseHeaders(const Headers& h);
