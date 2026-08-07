cmake_minimum_required(VERSION 3.22.1)

project("reelz")

# Build the shared library containing our native key function.
add_library(
        reelz
        SHARED
        native-lib.cpp
)

# Link the Android log library (needed if you add __android_log_print calls later).
find_library(log-lib log)

target_link_libraries(
        reelz
        ${log-lib}
)
