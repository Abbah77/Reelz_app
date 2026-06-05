cmake_minimum_required(VERSION 3.22.1)
project(streamscanner VERSION 1.0.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(streamscanner SHARED
    scanner.cpp
    m3u8_parser.cpp
    url_extractor.cpp
)
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(streamscanner ${log-lib} ${android-lib})
target_compile_options(streamscanner PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(streamscanner PRIVATE -Wl,--gc-sections)
