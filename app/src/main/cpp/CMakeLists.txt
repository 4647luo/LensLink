cmake_minimum_required(VERSION 3.22.1)

project(lianjingraw CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 最新 LibRaw master（0.23.0 快照）：包含 nikonhe.cpp，可解尼康 Z50II 的 HE/HE★ NEF。
# 不使用 AndroidLibRaw 的预编译 0.21.2，因为那版没有 Z50II HE 解码器。
set(LIBRAW_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libraw" CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENMP OFF CACHE BOOL "" FORCE)
set(ENABLE_LCMS OFF CACHE BOOL "" FORCE)
set(ENABLE_JASPER OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_RAWSPEED OFF CACHE BOOL "" FORCE)
set(ENABLE_DCRAW_DEBUG OFF CACHE BOOL "" FORCE)
set(ENABLE_X3FTOOLS OFF CACHE BOOL "" FORCE)
set(ENABLE_6BY9RPI OFF CACHE BOOL "" FORCE)
set(LIBRAW_INSTALL OFF CACHE BOOL "" FORCE)
set(LIBRAW_UNINSTALL_TARGET OFF CACHE BOOL "" FORCE)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/libraw-cmake"
                 "${CMAKE_CURRENT_BINARY_DIR}/libraw-build")

# ---------------------------------------------------------------------------
# Google libultrahdr（Ultra HDR 参考编解码器，v2.0.2，随源码内置）
# 依赖的 libjpeg-turbo 已按 arm64-v8a 预编译在 vendor/jpeg-prefix，构建不再联网。
# ---------------------------------------------------------------------------
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(UHDR_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_FUZZERS OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_JAVA OFF CACHE BOOL "" FORCE)
set(UHDR_ENABLE_HEIF OFF CACHE BOOL "" FORCE)
set(UHDR_ENABLE_GLES OFF CACHE BOOL "" FORCE)
set(UHDR_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(UHDR_ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_DEPS OFF CACHE BOOL "" FORCE)
# 同时写 ISO 21496-1 与 XMP hdrgm 两个包：Android 系统 Gainmap 解码走 XMP，
# Chrome / iOS / Google Photos 也认 XMP，兼容面最大（解码时库优先 ISO）
set(UHDR_WRITE_XMP ON CACHE BOOL "" FORCE)
set(UHDR_WRITE_ISO ON CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/vendor/libultrahdr"
                 "${CMAKE_CURRENT_BINARY_DIR}/libultrahdr-build")

add_library(lianjingraw SHARED raw_jni.cpp ultrahdr_jni.cpp)

target_include_directories(lianjingraw PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/vendor/jpeg-prefix/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libultrahdr")

target_compile_options(lianjingraw PRIVATE
        -O2
        -fvisibility=hidden
        -Wall
        -Wextra
        -Wno-unused-parameter
        -DNDEBUG)

target_link_libraries(lianjingraw PRIVATE
        libraw::libraw
        core
        "${CMAKE_CURRENT_SOURCE_DIR}/vendor/jpeg-prefix/lib/libjpeg.a"
        "${CMAKE_CURRENT_SOURCE_DIR}/vendor/jpeg-prefix/lib/libturbojpeg.a"
        jnigraphics
        log
        android)

# 只导出 JNI 入口，减小 .so 体积，也避免和静态 LibRaw 的符号冲突。
target_link_options(lianjingraw PRIVATE "-Wl,--exclude-libs,ALL")
