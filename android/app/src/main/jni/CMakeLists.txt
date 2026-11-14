cmake_minimum_required(VERSION 3.22)
project("opencyclone-adb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    add_definitions(-DDEBUG)
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(adb SHARED
    adb_pairing.cpp)

target_link_libraries(adb log boringssl::crypto_static cxx::cxx)

# 16 KB memory pages: newer phones refuse or crash on libraries whose LOAD segments are 4 KB aligned.
# NDK r27 (the one installed) still defaults to 4 KB; r28+ makes this the default.
target_link_options(adb PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET adb POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libadb.so")
endif ()
