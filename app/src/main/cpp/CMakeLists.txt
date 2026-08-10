cmake_minimum_required(VERSION 3.22.1)
project("reelz_native" CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimisation flags for release builds
add_compile_options(
    -O3
    -ffast-math
    -funroll-loops
    -fvectorize
    -DNDEBUG
)

add_library(
    reelz_native
    SHARED
    reelz_jni.cpp
    m3u8_parser.cpp
    header_forge.cpp
)

find_library(log-lib log)

target_link_libraries(
    reelz_native
    ${log-lib}
)
