# ---------------------------------------------------------------------------
# Varun Studio - HOST test harness for the native inference engine.
#
# This builds the *exact shipping* app/src/main/cpp/llama_session.cpp against a
# host (x86_64/arm64 Linux or macOS) build of llama.cpp, and links it to
# host_engine_test.cpp, which asserts the engine contract with 50 checks.
#
# It is deliberately separate from app/src/main/cpp/CMakeLists.txt: that one is
# an Android NDK cross-compile driven by Gradle and cannot run on a build
# machine. This one produces a binary you can actually execute, so the C++
# layer is verified by a real test run rather than by inspection.
#
# Usage (see run_host_tests.sh, which automates all of this):
#   cmake -S tools/hosttest -B build-hosttest \
#         -DVARUN_LLAMA_CPP_SRC=/path/to/llama.cpp \
#         -DVARUN_LLAMA_CPP_BUILD=/path/to/llama.cpp/build-host
#   cmake --build build-hosttest -j
#   ./build-hosttest/host_engine_test /path/to/synthetic-tiny.gguf
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.16)
project(varun_host_tests CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(VARUN_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../..")
set(VARUN_CPP_DIR "${VARUN_REPO_ROOT}/app/src/main/cpp")

# A pre-extracted llama.cpp source tree. Required: the host harness does not
# fetch upstream itself, so the test is reproducible against a known revision.
set(VARUN_LLAMA_CPP_SRC "" CACHE PATH "Path to a llama.cpp source tree")
# A pre-built llama.cpp build tree (the one containing bin/libllama.*).
set(VARUN_LLAMA_CPP_BUILD "" CACHE PATH "Path to a llama.cpp build tree")

if(NOT VARUN_LLAMA_CPP_SRC OR NOT EXISTS "${VARUN_LLAMA_CPP_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "VARUN_LLAMA_CPP_SRC must point at a llama.cpp source tree. "
        "Pass -DVARUN_LLAMA_CPP_SRC=/path/to/llama.cpp (run_host_tests.sh does this for you).")
endif()

find_package(Threads REQUIRED)

# ---------------------------------------------------------------------------
# Locate the host llama library and headers.
# ---------------------------------------------------------------------------
set(_llama_inc "${VARUN_LLAMA_CPP_SRC}/include")
set(_ggml_inc "${VARUN_LLAMA_CPP_SRC}/ggml/include")

set(_search_dirs "")
if(VARUN_LLAMA_CPP_BUILD)
    list(APPEND _search_dirs
        "${VARUN_LLAMA_CPP_BUILD}/bin"
        "${VARUN_LLAMA_CPP_BUILD}/lib"
        "${VARUN_LLAMA_CPP_BUILD}/lib64"
        "${VARUN_LLAMA_CPP_BUILD}/src"
    )
endif()
list(APPEND _search_dirs "${_llama_inc}/../build/bin" "${_llama_inc}/../build/lib")

find_library(LLAMA_HOST_LIB
    NAMES llama
    PATHS ${_search_dirs}
    NO_DEFAULT_PATH
)
if(NOT LLAMA_HOST_LIB)
    find_library(LLAMA_HOST_LIB NAMES llama)
endif()
if(NOT LLAMA_HOST_LIB)
    message(FATAL_ERROR
        "Could not find a host libllama. Build llama.cpp first, e.g.\n"
        "  cmake -S ${VARUN_LLAMA_CPP_SRC} -B ${VARUN_LLAMA_CPP_SRC}/build-host -DBUILD_SHARED_LIBS=ON\n"
        "  cmake --build ${VARUN_LLAMA_CPP_SRC}/build-host --target llama -j\n"
        "then pass -DVARUN_LLAMA_CPP_BUILD=${VARUN_LLAMA_CPP_SRC}/build-host")
endif()
message(STATUS "varun host tests: llama library = ${LLAMA_HOST_LIB}")

get_filename_component(_llama_libdir "${LLAMA_HOST_LIB}" DIRECTORY)

# ---------------------------------------------------------------------------
# The test executable: shipping session code + the assertion harness.
# ---------------------------------------------------------------------------
add_executable(host_engine_test
    "${VARUN_CPP_DIR}/llama_session.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/host_engine_test.cpp"
)

target_include_directories(host_engine_test PRIVATE
    "${VARUN_CPP_DIR}"
    "${_llama_inc}"
    "${_ggml_inc}"
)

target_link_libraries(host_engine_test PRIVATE "${LLAMA_HOST_LIB}")
target_link_libraries(host_engine_test PRIVATE Threads::Threads)

# The shipping code is held to the same warning bar as the Android build.
# -Werror here is what caught three real API bugs before any APK existed.
target_compile_options(host_engine_test PRIVATE -Wall -Wextra -Werror)

# Make the shared llama library resolvable at run time without an install step.
set_target_properties(host_engine_test PROPERTIES
    BUILD_RPATH   "${_llama_libdir}"
    INSTALL_RPATH "${_llama_libdir}"
)

enable_testing()
# A model path is required; run_host_tests.sh synthesises one. When invoked
# directly without a model the harness reports the missing file as a failure.
add_test(NAME host_engine_test
    COMMAND host_engine_test "${VARUN_TEST_MODEL}")
