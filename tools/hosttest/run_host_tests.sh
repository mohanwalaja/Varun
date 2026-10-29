#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Varun Studio - run the native engine host tests.
#
# Builds llama.cpp for THIS machine, synthesises a tiny valid GGUF, compiles the
# *shipping* app/src/main/cpp/llama_session.cpp with -Wall -Wextra -Werror, and
# runs the 50-assertion harness against it.
#
# Why this exists: the Android NDK cross-compile cannot be executed on a build
# machine, so without this harness the C++ layer would only ever be verified by
# a successful compile. Here it is verified by actually running it.
#
# Requirements: cmake >= 3.16, a C++17 compiler, python3, git.
# Optional:     pip packages `gguf` and `numpy` (auto-installed if missing).
#
# Usage:
#   ./tools/hosttest/run_host_tests.sh                 # clone + build everything
#   VARUN_LLAMA_CPP_SRC=/path/to/llama.cpp ./tools/hosttest/run_host_tests.sh
#   VARUN_KEEP_WORKDIR=1 ./tools/hosttest/run_host_tests.sh
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKDIR="${VARUN_WORKDIR:-${REPO_ROOT}/build-hosttest}"
SRC_DIR="${VARUN_LLAMA_CPP_SRC:-${WORKDIR}/llama.cpp}"
BUILD_LLAMA="${SRC_DIR}/build-host"
MODEL="${WORKDIR}/synthetic-tiny.gguf"

# Must match app/src/main/cpp/CMakeLists.txt exactly.
LLAMA_REPO="https://github.com/ggml-org/llama.cpp.git"
LLAMA_TAG="bf48103ad164d919fbad6a28359c3f728e4ad942"   # llama.cpp b11979, MIT

JOBS="${VARUN_JOBS:-$( (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2) )}"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
say "Checking prerequisites"
command -v cmake  >/dev/null 2>&1 || die "cmake not found. Install cmake >= 3.16."
command -v git    >/dev/null 2>&1 || die "git not found."
command -v python3 >/dev/null 2>&1 || die "python3 not found."
CXX_BIN="${CXX:-}"
if [ -z "$CXX_BIN" ]; then
    for c in g++ clang++ c++; do
        if command -v "$c" >/dev/null 2>&1; then CXX_BIN="$c"; break; fi
    done
fi
[ -n "$CXX_BIN" ] || die "no C++ compiler found (looked for g++, clang++, c++)."
printf 'cmake   : %s\n' "$(cmake --version | head -1)"
printf 'compiler: %s\n' "$("$CXX_BIN" --version | head -1)"
printf 'jobs    : %s\n' "$JOBS"

mkdir -p "$WORKDIR"

# ------------------------------------------------------------ llama.cpp src
if [ -f "${SRC_DIR}/CMakeLists.txt" ]; then
    say "Using llama.cpp source at ${SRC_DIR}"
else
    say "Cloning llama.cpp (pinned ${LLAMA_TAG:0:12})"
    # Full clone: the pinned SHA is not the tip, so --depth 1 cannot fetch it.
    git clone --filter=blob:none --no-checkout "$LLAMA_REPO" "$SRC_DIR"
    git -C "$SRC_DIR" checkout --quiet "$LLAMA_TAG"
fi

ACTUAL_SHA="$(git -C "$SRC_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
printf 'llama.cpp HEAD: %s\n' "$ACTUAL_SHA"
if [ "$ACTUAL_SHA" != "$LLAMA_TAG" ] && [ "$ACTUAL_SHA" != "unknown" ]; then
    printf '\033[1;33mWARNING: source tree is not at the pinned commit.\033[0m\n'
    printf '         expected %s\n' "$LLAMA_TAG"
fi

# --------------------------------------------------------- build llama.cpp
say "Building host llama.cpp (shared library)"
cmake -S "$SRC_DIR" -B "$BUILD_LLAMA" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_CURL=OFF \
    -DGGML_NATIVE=ON \
    -DGGML_CUDA=OFF -DGGML_METAL=OFF -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF \
    >/dev/null
# Only the library target is needed; building the CLI tools wastes minutes.
cmake --build "$BUILD_LLAMA" --target llama -j "$JOBS" >/dev/null

# ------------------------------------------------------- synthetic GGUF
say "Synthesising a tiny valid GGUF model"
if ! python3 -c "import gguf, numpy" >/dev/null 2>&1; then
    printf 'installing python deps (gguf, numpy)...\n'
    python3 -m pip install --quiet --disable-pip-version-check \
        ${VARUN_PIP_FLAGS:-} gguf numpy \
        || die "could not install 'gguf' and 'numpy'. Install them or set VARUN_TEST_MODEL to an existing .gguf."
fi
if [ -n "${VARUN_TEST_MODEL:-}" ] && [ -f "$VARUN_TEST_MODEL" ]; then
    MODEL="$VARUN_TEST_MODEL"
    printf 'using provided model: %s\n' "$MODEL"
else
    python3 "${REPO_ROOT}/tools/hosttest/make_synthetic_gguf.py" "$MODEL"
fi
printf 'model: %s (%s bytes)\n' "$MODEL" "$(wc -c < "$MODEL" | tr -d ' ')"

# ------------------------------------------------------------- build tests
say "Building the host test harness (-Wall -Wextra -Werror)"
cmake -S "${REPO_ROOT}/tools/hosttest" -B "${WORKDIR}/tests" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_COMPILER="$CXX_BIN" \
    -DVARUN_LLAMA_CPP_SRC="$SRC_DIR" \
    -DVARUN_LLAMA_CPP_BUILD="$BUILD_LLAMA" \
    -DVARUN_TEST_MODEL="$MODEL"
cmake --build "${WORKDIR}/tests" -j "$JOBS"

# ------------------------------------------------------------------- run
say "Running host engine tests"
set +e
"${WORKDIR}/tests/host_engine_test" "$MODEL"
STATUS=$?
set -e

if [ "$STATUS" -eq 0 ]; then
    say "HOST ENGINE TESTS PASSED"
else
    printf '\n\033[1;31mHOST ENGINE TESTS FAILED (exit %s)\033[0m\n' "$STATUS"
fi

if [ -z "${VARUN_KEEP_WORKDIR:-}" ]; then
    printf '\nWork dir kept at %s (set VARUN_KEEP_WORKDIR=1 to be sure).\n' "$WORKDIR"
fi
exit "$STATUS"
