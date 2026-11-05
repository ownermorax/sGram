#!/bin/sh
# Builds libsgramhook.so and copies it with liblsplant.so into TMessagesProj/src/main/jniLibs.
# prebuilt/: liblsplant.so — LSPlant master (CI build 2026-10-31, Android 5–17), libdobby.a — io.github.vvb2060.ndk:dobby:1.2
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=$SDK/ndk/27.2.12479018
CMAKE=$SDK/cmake/3.22.1/bin
OUT=../TMessagesProj/src/main/jniLibs
for ABI in ${ABIS:-arm64-v8a x86_64}; do
    rm -rf build/$ABI
    $CMAKE/cmake -S . -B build/$ABI -G Ninja -DCMAKE_MAKE_PROGRAM=$CMAKE/ninja \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release >/dev/null
    $CMAKE/cmake --build build/$ABI
    mkdir -p $OUT/$ABI
    $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded -o $OUT/$ABI/libsgramhook.so build/$ABI/libsgramhook.so
    cp prebuilt/$ABI/liblsplant.so $OUT/$ABI/
done
