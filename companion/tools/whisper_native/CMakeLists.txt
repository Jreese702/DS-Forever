# DS Forever's offline voice typing: libdsfwhisper.so = whisper.cpp v1.9.5 (MIT, vendored in ./whisper.cpp by vendor_whisper.sh), CPU
# only, statically linked into one small JNI library (jni/whisper_jni.cpp). Built by build_android.sh for arm64-v8a, Release.
cmake_minimum_required(VERSION 3.16)
project(dsfwhisper C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# dotprod + fp16 run on both Thors (8 Gen 2, and the Lite's 865 = Cortex-A77, ARMv8.2); NEVER i8mm / SVE: the Lite has neither
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)          # ggml's own thread pool: no libomp.so in the APK
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)        # tinyBLAS: the f16 matrix products on NEON
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(DSF_OPT -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
add_compile_options(${DSF_OPT})

add_subdirectory(whisper.cpp whisper EXCLUDE_FROM_ALL)

add_library(dsfwhisper SHARED jni/whisper_jni.cpp)
target_link_libraries(dsfwhisper PRIVATE whisper log)
target_link_options(dsfwhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--build-id=none)
