# Varun Studio - native build.
#
# Builds ONE shared library (libvarunllama.so) that statically links llama.cpp,
# so the APK ships a single .so per ABI instead of four.
#
# llama.cpp is fetched at configure time from a PINNED commit on github.com.
# Nothing about the runtime is vendored as an opaque binary blob: the exact
# source revision is recorded below and re-fetched on every clean build.
cmake_minimum_required(VERSION 3.22.1)
project(varunllama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Pinned upstream runtime
# ---------------------------------------------------------------------------
# llama.cpp b11979 == commit bf48103ad164d919fbad6a28359c3f728e4ad942 (MIT)
set(VARUN_LLAMA_CPP_TAG "bf48103ad164d919fbad6a28359c3f728e4ad942"
    CACHE STRING "Pinned llama.cpp commit")
set(VARUN_LLAMA_CPP_REPO "https://github.com/ggml-org/llama.cpp.git"
    CACHE STRING "llama.cpp source repository")

# Offline / air-gapped builds: point -DVARUN_LLAMA_CPP_SRC=/path/to/llama.cpp
# at a pre-extracted source tree (e.g. a directory you pushed to the device or
# a checkout cached by CI) and no network access is required.
set(VARUN_LLAMA_CPP_SRC "" CACHE PATH "Optional pre-extracted llama.cpp source dir")

if(VARUN_LLAMA_CPP_SRC AND EXISTS "${VARUN_LLAMA_CPP_SRC}/CMakeLists.txt")
    message(STATUS "varun: using local llama.cpp source at ${VARUN_LLAMA_CPP_SRC}")
    add_subdirectory(${VARUN_LLAMA_CPP_SRC} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)
else()
    include(FetchContent)
    message(STATUS "varun: fetching llama.cpp ${VARUN_LLAMA_CPP_TAG}")
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY ${VARUN_LLAMA_CPP_REPO}
        GIT_TAG        ${VARUN_LLAMA_CPP_TAG}
        GIT_SHALLOW    FALSE
        GIT_PROGRESS   TRUE
    )
    # Trim the upstream build to exactly what an inference-only Android app needs.
    set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
    set(LLAMA_CONNECT        OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
    set(LLAMA_LLGUIDANCE     OFF CACHE BOOL "" FORCE)
    set(LLAMA_STANDALONE     OFF CACHE BOOL "" FORCE)
    set(LLAMA_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
    # ggml backend selection: CPU only. No Vulkan/OpenCL/Metal -> smaller APK,
    # no GPU driver compatibility matrix to maintain on 8 GB phones.
    set(GGML_VULKAN   OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
    set(GGML_METAL    OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
    set(GGML_SYCL     OFF CACHE BOOL "" FORCE)
    set(GGML_RPC      OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS     OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)  # must be OFF: APK runs on unknown ARM cores
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BUILD_NUMBER "1" CACHE STRING "" FORCE)
    FetchContent_MakeAvailable(llama_cpp)
    set(LLAMA_INCLUDE_DIR ${llama_cpp_SOURCE_DIR}/include)
endif()

if(NOT DEFINED LLAMA_INCLUDE_DIR)
    set(LLAMA_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../.cxx/llama/include)
endif()

# ---------------------------------------------------------------------------
# Our library
# ---------------------------------------------------------------------------
add_library(varunllama SHARED
    llama_session.cpp
    llama_bridge.cpp
)

target_include_directories(varunllama PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

# Static-link llama.cpp so the APK contains exactly one .so per ABI.
target_link_libraries(varunllama PRIVATE
    llama
    ggml
    log
)

# Release tuning for phone CPUs.
target_compile_options(varunllama PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -fno-exceptions
    -fno-rtti
)
target_link_options(varunllama PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

# JNI symbols must stay visible even with -fvisibility=hidden.
set_target_properties(varunllama PROPERTIES
    OUTPUT_NAME "varunllama"
)
target_compile_definitions(varunllama PRIVATE JNIEXPORT=__attribute__\(\(visibility\("default"\)\)\))
