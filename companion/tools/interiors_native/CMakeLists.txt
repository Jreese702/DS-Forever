# Track B: the interiors generator, one C++ source set for the phone (libinteriors.so, arm64-v8a) and the PC host build
# (interiors_host.exe) that D's parity harness compares with the Python pipeline. Neither build reads server data, and
# generated output never goes into git or the APK.
#
#   RECAST_SOURCE   upstream recastnavigation checkout at the pinned v1.6.0 commit (outside git)
#   CASCLIB_SOURCE  CascLib checkout (host only: local-CASC reads + its bundled zlib)
cmake_minimum_required(VERSION 3.16)
project(wc_interiors LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(RECAST_SOURCE "" CACHE PATH "pinned upstream recastnavigation v1.6.0 checkout")
set(CASCLIB_SOURCE "" CACHE PATH "CascLib checkout (host build only)")
set(WC_RECAST_COMMIT "6dc1667f580357e8a2154c28b7867bea7e8ad3a7")

if(NOT EXISTS "${RECAST_SOURCE}/Recast/Include/Recast.h")
    message(FATAL_ERROR "Set RECAST_SOURCE to the private upstream Recast v1.6.0 checkout")
endif()
find_package(Git QUIET)
if(GIT_FOUND)
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${RECAST_SOURCE}" rev-parse HEAD
        OUTPUT_VARIABLE RECAST_HEAD OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE GIT_RESULT)
    if(NOT GIT_RESULT EQUAL 0 OR NOT RECAST_HEAD STREQUAL WC_RECAST_COMMIT)
        message(FATAL_ERROR "Recast must be the pinned v1.6.0 commit ${WC_RECAST_COMMIT}, found '${RECAST_HEAD}'")
    endif()
else()
    message(FATAL_ERROR "git is required to verify the pinned Recast commit")
endif()

file(GLOB RECAST_SOURCES "${RECAST_SOURCE}/Recast/Source/*.cpp")
add_library(wc_recast STATIC ${RECAST_SOURCES})
target_include_directories(wc_recast PUBLIC "${RECAST_SOURCE}/Recast/Include")

if(ANDROID)
    find_library(ZLIB_LIB z REQUIRED)
    add_library(wc_zlib INTERFACE)
    target_link_libraries(wc_zlib INTERFACE ${ZLIB_LIB})
else()
    if(NOT EXISTS "${CASCLIB_SOURCE}/src/CascLib.h")
        message(FATAL_ERROR "Set CASCLIB_SOURCE to the CascLib checkout for the host build")
    endif()
    set(ZDIR "${CASCLIB_SOURCE}/src/zlib")
    add_library(wc_zlib STATIC ${ZDIR}/adler32.c ${ZDIR}/crc32.c ${ZDIR}/deflate.c ${ZDIR}/inffast.c ${ZDIR}/inflate.c
        ${ZDIR}/inftrees.c ${ZDIR}/trees.c ${ZDIR}/zutil.c)
    target_include_directories(wc_zlib PUBLIC ${ZDIR})
endif()

file(GLOB CORE_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
add_library(wc_interiors_core STATIC ${CORE_SOURCES})
target_include_directories(wc_interiors_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_compile_definitions(wc_interiors_core PRIVATE WC_RECAST_COMMIT="${WC_RECAST_COMMIT}")
target_link_libraries(wc_interiors_core PUBLIC wc_recast wc_zlib)
if(MSVC)
    target_compile_options(wc_interiors_core PRIVATE /W3 /utf-8 /fp:precise)
    target_compile_definitions(wc_interiors_core PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX)
else()
    # no fused multiply-add: keep arithmetic identical between arm64 and the x64 host build
    target_compile_options(wc_interiors_core PRIVATE -Wall -ffp-contract=off)
    target_compile_options(wc_recast PRIVATE -ffp-contract=off)
endif()

if(ANDROID)
    add_library(interiors SHARED jni/interiors_jni.cpp)
    target_link_libraries(interiors PRIVATE wc_interiors_core log)
    target_compile_definitions(interiors PRIVATE WC_RECAST_COMMIT="${WC_RECAST_COMMIT}")
    target_link_options(interiors PRIVATE -Wl,--gc-sections -Wl,-soname,libinteriors.so -Wl,-z,max-page-size=16384)
    set_target_properties(interiors PROPERTIES CXX_VISIBILITY_PRESET hidden)
else()
    set(CSRC "${CASCLIB_SOURCE}/src")
    set(CASC_FILES common/Common common/Directory common/Csv common/FileStream common/FileTree common/ListFile common/Mime
        common/RootHandler common/Sockets hashes/md5 hashes/sha1 overwatch/apm overwatch/cmf overwatch/aes CascDecompress
        CascDecrypt CascDumpData CascFiles CascFindFile CascIndexFiles CascOpenFile CascOpenStorage CascReadFile
        CascRootFile_Diablo3 CascRootFile_Install CascRootFile_MNDX CascRootFile_Text CascRootFile_TVFS CascRootFile_OW
        CascRootFile_WoW)
    set(CASC_SOURCES "${CSRC}/jenkins/lookup3.c")
    foreach(f ${CASC_FILES})
        list(APPEND CASC_SOURCES "${CSRC}/${f}.cpp")
    endforeach()
    add_library(wc_casc STATIC ${CASC_SOURCES})
    target_include_directories(wc_casc PUBLIC "${CSRC}")
    target_compile_definitions(wc_casc PUBLIC CASCLIB_NO_AUTO_LINK_LIBRARY CASCLIB_NODEBUG _CRT_SECURE_NO_WARNINGS UNICODE _UNICODE)
    target_link_libraries(wc_casc PUBLIC wc_zlib ws2_32)

    add_executable(interiors_host host/host_main.cpp)
    target_link_libraries(interiors_host PRIVATE wc_interiors_core wc_casc psapi)
    target_compile_definitions(interiors_host PRIVATE NOMINMAX _CRT_SECURE_NO_WARNINGS)

    # The same JNI layer as a Windows DLL for the JVM end-to-end test (jvm_test/run.ps1): the real NativeIndoorGenerator,
    # IndoorBakeStore and JNI marshalling, reading the local PC install through a host-only CASC binding.
    find_package(JNI QUIET)
    if(JNI_FOUND)
        add_library(interiors_jvm SHARED jni/interiors_jni.cpp host/jvm_casc.cpp)
        set_target_properties(interiors_jvm PROPERTIES OUTPUT_NAME interiors)
        target_include_directories(interiors_jvm PRIVATE ${JNI_INCLUDE_DIRS})
        target_compile_definitions(interiors_jvm PRIVATE WC_RECAST_COMMIT="${WC_RECAST_COMMIT}" NOMINMAX)
        target_link_libraries(interiors_jvm PRIVATE wc_interiors_core wc_casc)
    endif()

    add_executable(raster_probe tests/raster_probe.cpp)
    target_link_libraries(raster_probe PRIVATE wc_interiors_core)
    target_compile_definitions(raster_probe PRIVATE NOMINMAX)
endif()
