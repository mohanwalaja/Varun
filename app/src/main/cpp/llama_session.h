// Varun Studio - on-device inference core.
//
// Pure C++ wrapper around llama.cpp. Deliberately contains NO JNI types so
// that it can be compiled and exercised on a desktop host for automated
// testing, and linked into the Android APK unchanged.
//
// Runtime: llama.cpp (MIT) pinned in app/src/main/cpp/CMakeLists.txt.
#ifndef VARUN_LLAMA_SESSION_H
#define VARUN_LLAMA_SESSION_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace varun {
namespace llm {

// Per-load configuration.
struct EngineConfig {
    std::string model_path;
    int32_t n_ctx = 2048;        // total context (prompt + generation)
    int32_t n_batch = 512;       // tokens decoded per llama_decode call
    int32_t n_threads = 4;       // generation threads
    int32_t n_threads_batch = 4; // prompt-processing threads
    bool use_mmap = true;        // mmap the GGUF (saves RAM, needed for big models)
    bool check_tensors = false;  // validate tensors on load (slow)
};

// Per-request sampling configuration.
struct GenerateConfig {
    int32_t max_tokens = 256;
    float temperature = 0.2f;
    int32_t top_k = 40;
    float top_p = 0.95f;
    float min_p = 0.05f;
    float repeat_penalty = 1.1f;
    int32_t repeat_last_n = 64;
    uint32_t seed = 0;  // 0 => derive from clock
    std::vector<std::string> stop_strings;
    bool apply_chat_template = false;
};

// Reported back to the UI for the "model info / memory" panel.
struct EngineStats {
    bool loaded = false;
    std::string description;
    std::string ftype;
    int32_t n_vocab = 0;
    int32_t n_ctx = 0;
    int32_t n_ctx_train = 0;
    int32_t n_embd = 0;
    int32_t n_layer = 0;
    uint64_t n_params = 0;
    uint64_t model_bytes = 0;
    int32_t n_threads = 0;
    // measured during the last generate() call
    int32_t last_prompt_tokens = 0;
    int32_t last_generated_tokens = 0;
    double last_prompt_tokens_per_sec = 0.0;
    double last_tokens_per_sec = 0.0;
    int64_t last_prompt_ms = 0;
    int64_t last_generate_ms = 0;
};

// Called once per generated token. Return false to abort generation early.
// `piece` is the raw detokenized fragment (may be a partial UTF-8 sequence).
using TokenCallback =
    std::function<bool(const std::string& piece, int32_t token_id, int32_t n_generated)>;

// Called while the model is loading so the UI can show progress.
using ProgressCallback = std::function<void(float progress)>;

// Result codes for generate().
enum class GenerateResult {
    kOk = 0,
    kCancelled = 1,
    kStopped = 2,          // stop string or end-of-generation token
    kMaxTokens = 3,        // hit max_tokens
    kContextFull = 4,      // prompt does not fit in n_ctx
    kDecodeFailed = 5,     // llama_decode returned an error
    kOutOfMemory = 6,      // allocation / KV cache failure
    kNotLoaded = 7,
    kInvalidSampler = 8,
};

const char* GenerateResultName(GenerateResult r);

class Session {
public:
    Session();
    ~Session();

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    // llama.cpp build version, for the About/diagnostics screen.
    static std::string RuntimeVersion();
    static bool SupportsMmap();

    // Load a GGUF from disk. Returns false and fills `error` on failure.
    // Safe to call again after unload(). Reports OOM distinctly so the UI can
    // suggest a smaller quant instead of crashing.
    bool Load(const EngineConfig& cfg, ProgressCallback progress, std::string* error);

    // Release model + context memory. Blocks until an in-flight generate()
    // has observed the cancellation flag and returned.
    void Unload();

    bool IsLoaded() const;

    EngineStats Stats() const;

    std::vector<int32_t> Tokenize(const std::string& text, bool add_special) const;
    std::string Detokenize(const std::vector<int32_t>& tokens) const;

    // Apply the model's own chat template (falls back to a generic one).
    // `messages` is a list of (role, content) pairs.
    std::string ApplyChatTemplate(
        const std::vector<std::pair<std::string, std::string>>& messages,
        bool add_assistant_suffix) const;

    // Drop the KV cache so a new conversation does not inherit old context.
    void ResetContext();

    // Synchronous generation. MUST be called off the Android main thread.
    // `cancel` is polled between tokens; setting it aborts promptly.
    GenerateResult Generate(const std::string& prompt,
                            const GenerateConfig& cfg,
                            const TokenCallback& on_token,
                            const std::atomic<bool>* cancel,
                            std::string* error);

    // Approximate KV-cache bytes for a given context length, used by the
    // memory estimator before a model is ever loaded.
    static uint64_t EstimateKvCacheBytes(int32_t n_layer,
                                         int32_t n_head_kv,
                                         int32_t n_embd,
                                         int32_t n_ctx);

private:
    mutable std::mutex mu_;  // guards model_/ctx_ lifetime
    std::atomic<bool> cancel_requested_{false};
    void* model_ = nullptr;   // struct llama_model*
    void* ctx_ = nullptr;     // struct llama_context*
    const void* vocab_ = nullptr;  // const struct llama_vocab*
    EngineConfig cfg_;
    EngineStats stats_;
};

}  // namespace llm
}  // namespace varun

#endif  // VARUN_LLAMA_SESSION_H
