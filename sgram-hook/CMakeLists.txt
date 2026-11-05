cmake_minimum_required(VERSION 3.18.1)
project(sgramhook C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(lsplant SHARED IMPORTED)
set_target_properties(lsplant PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/liblsplant.so)
add_library(dobby STATIC IMPORTED)
set_target_properties(dobby PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libdobby.a)

add_library(sgramhook SHARED
        src/sgramhook.cpp
        src/xz/xz_crc32.c
        src/xz/xz_crc64.c
        src/xz/xz_dec_lzma2.c
        src/xz/xz_dec_stream.c)
target_include_directories(sgramhook PRIVATE include src)
target_compile_definitions(sgramhook PRIVATE XZ_USE_CRC64 XZ_DEC_ANY_CHECK)
target_compile_options(sgramhook PRIVATE -fvisibility=hidden -fno-exceptions -fno-rtti -O2)
target_link_options(sgramhook PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(sgramhook lsplant dobby log)
