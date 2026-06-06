cmake_minimum_required(VERSION 3.22.1)
project("reelz_native" CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    reelz_native
    SHARED
    reelz_native.cpp
    url_builder.cpp
    m3u8_parser.cpp
    header_forge.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    reelz_native
    ${log-lib}
    ${android-lib}
)

target_compile_options(reelz_native PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -DNDEBUG
)

target_link_options(reelz_native PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)
