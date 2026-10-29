// Varun Studio - on-device inference core (implementation).
// See llama_session.h for the contract. Written against llama.cpp b11979.
#include "llama_session.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>

#include "llama.h"

namespace varun {
namespace llm {
namespace {

using Clock = std::chrono::steady_clock;

int64_t UsSince(const Clock::time_point& t0) {
    return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - t0).count();
}

// Shared tokenizer with correct handling of llama.cpp's negated-size probe
// convention. Used by both Tokenize() and Generate() so the two cannot drift.
std::vector<int32_t> TokenizeWith(const llama_vocab* vocab, const std::string& text,
                                  bool add_special) {
    std::vector<int32_t> out;
    if (vocab == nullptr) return out;
    const int32_t n = static_cast<int32_t>(text.size());
    int32_t probe = llama_tokenize(vocab, text.c_str(), n, nullptr, 0, add_special, true);
    if (probe >= 0) return out;  // zero tokens
    int32_t needed = -probe;
    for (int attempt = 0; attempt < 8; ++attempt) {
        out.resize(static_cast<size_t>(needed));
        const int32_t written = llama_tokenize(
            vocab, text.c_str(), n, reinterpret_cast<llama_token*>(out.data()), needed,
            add_special, true);
        if (written >= 0) {
            out.resize(static_cast<size_t>(written));
            return out;
        }
        needed = -written;
    }
    out.clear();
    return out;
}

inline llama_model* AsModel(void* p) { return static_cast<llama_model*>(p); }
inline llama_context* AsCtx(void* p) { return static_cast<llama_context*>(p); }
inline const llama_vocab* AsVocab(const void* p) {
    return static_cast<const llama_vocab*>(p);
}

// One-time backend init, guarded so concurrent Sessions are safe.
void EnsureBackend() {
    static std::once_flag flag;
    std::call_once(flag, [] { llama_backend_init(); });
}

// Fill a batch slot. llama.cpp removed the llama_batch_add helper from the
// public API, so we do it explicitly.
void BatchAdd(llama_batch& batch, llama_token tok, llama_pos pos, llama_seq_id seq, bool logits) {
    const int32_t i = batch.n_tokens++;
    batch.token[i] = tok;
    batch.pos[i] = pos;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = seq;
    batch.logits[i] = logits ? 1 : 0;
}

// llama.cpp writes its own logs to stderr; on Android we route them nowhere
// noisy. Keeps secrets/paths out of logcat by default.
void LlamaLogSink(enum ggml_log_level level, const char* text, void* udata) {
    (void)level;
    (void)udata;
    (void)text;  // deliberately silent: model paths and prompts must not leak to logcat
}

}  // namespace

const char* GenerateResultName(GenerateResult r) {
    switch (r) {
        case GenerateResult::kOk: return "ok";
        case GenerateResult::kCancelled: return "cancelled";
        case GenerateResult::kStopped: return "stopped";
        case GenerateResult::kMaxTokens: return "max_tokens";
        case GenerateResult::kContextFull: return "context_full";
        case GenerateResult::kDecodeFailed: return "decode_failed";
        case GenerateResult::kOutOfMemory: return "out_of_memory";
        case GenerateResult::kNotLoaded: return "not_loaded";
        case GenerateResult::kInvalidSampler: return "invalid_sampler";
    }
    return "unknown";
}

Session::Session() = default;

Session::~Session() { Unload(); }

std::string Session::RuntimeVersion() {
    const char* v = llama_version();
    return v ? std::string(v) : std::string("unknown");
}

bool Session::SupportsMmap() { return llama_supports_mmap(); }

bool Session::Load(const EngineConfig& cfg, ProgressCallback progress, std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    if (model_ != nullptr) {
        if (error) *error = "A model is already loaded. Unload it first.";
        return false;
    }
    if (cfg.model_path.empty()) {
        if (error) *error = "Model path is empty.";
        return false;
    }

    EnsureBackend();
    llama_log_set(LlamaLogSink, nullptr);
    cancel_requested_.store(false);
    cfg_ = cfg;
    stats_ = EngineStats{};

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU-only: no GPU offload on this build
    mparams.load_mode = cfg.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    mparams.check_tensors = cfg.check_tensors;
    mparams.progress_callback = nullptr;
    if (progress) {
        // progress_callback returns false to abort; we never abort here.
        static thread_local ProgressCallback* cb_slot = nullptr;
        cb_slot = &progress;
        mparams.progress_callback = [](float p, void* ud) -> bool {
            (void)ud;
            if (cb_slot && *cb_slot) (*cb_slot)(p);
            return true;
        };
        mparams.progress_callback_user_data = nullptr;
    }

    llama_model* model = llama_model_load_from_file(cfg.model_path.c_str(), mparams);
    if (model == nullptr) {
        if (error) {
            *error = "llama.cpp could not load this GGUF file. It may be corrupt, "
                     "unsupported by this runtime build, or too large for available RAM.";
        }
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(cfg.n_ctx > 0 ? cfg.n_ctx : 512);
    cparams.n_batch = static_cast<uint32_t>(cfg.n_batch > 0 ? cfg.n_batch : 512);
    cparams.n_ubatch = cparams.n_batch;
    cparams.n_threads = cfg.n_threads > 0 ? cfg.n_threads : 4;
    cparams.n_threads_batch = cfg.n_threads_batch > 0 ? cfg.n_threads_batch : cparams.n_threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        if (error) {
            *error = "Model loaded but the context/KV cache could not be allocated. "
                     "Reduce the context length or free RAM and try again.";
        }
        return false;
    }

    model_ = model;
    ctx_ = ctx;
    vocab_ = llama_model_get_vocab(model);

    char desc[256] = {0};
    llama_model_desc(model, desc, sizeof(desc));
    stats_.description = desc;
    stats_.ftype = llama_ftype_name(llama_model_ftype(model));
    stats_.n_vocab = llama_vocab_n_tokens(AsVocab(vocab_));
    stats_.n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    stats_.n_ctx_train = llama_model_n_ctx_train(model);
    stats_.n_embd = llama_model_n_embd(model);
    stats_.n_layer = llama_model_n_layer(model);
    stats_.n_params = llama_model_n_params(model);
    stats_.model_bytes = llama_model_size(model);
    stats_.n_threads = static_cast<int32_t>(cparams.n_threads);
    stats_.loaded = true;
    if (error) error->clear();
    return true;
}

void UnloadLockedImpl(void*& model, void*& ctx) {
    if (ctx != nullptr) {
        llama_free(AsCtx(ctx));
        ctx = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(AsModel(model));
        model = nullptr;
    }
}

void Session::Unload() {
    // Ask any in-flight generation to stop before taking the lock, otherwise
    // Unload() would deadlock waiting for Generate() to release mu_.
    cancel_requested_.store(true);
    std::lock_guard<std::mutex> lock(mu_);
    UnloadLockedImpl(model_, ctx_);
    vocab_ = nullptr;
    stats_ = EngineStats{};
    cfg_ = EngineConfig{};
}

bool Session::IsLoaded() const {
    std::lock_guard<std::mutex> lock(mu_);
    return model_ != nullptr && ctx_ != nullptr;
}

EngineStats Session::Stats() const {
    std::lock_guard<std::mutex> lock(mu_);
    return stats_;
}

std::vector<int32_t> Session::Tokenize(const std::string& text, bool add_special) const {
    std::lock_guard<std::mutex> lock(mu_);
    return TokenizeWith(AsVocab(vocab_), text, add_special);
}

std::string Session::Detokenize(const std::vector<int32_t>& tokens) const {
    std::lock_guard<std::mutex> lock(mu_);
    std::string out;
    if (vocab_ == nullptr) return out;
    char buf[1024];
    for (int32_t t : tokens) {
        const int32_t n = llama_token_to_piece(AsVocab(vocab_), t, buf, sizeof(buf), 0, false);
        if (n < 0) continue;  // buffer too small for this piece; skip
        out.append(buf, static_cast<size_t>(n));
    }
    return out;
}

std::string Session::ApplyChatTemplate(
    const std::vector<std::pair<std::string, std::string>>& messages,
    bool add_assistant_suffix) const {
    std::lock_guard<std::mutex> lock(mu_);
    if (model_ == nullptr) return std::string();

    std::vector<llama_chat_message> chat;
    chat.reserve(messages.size());
    for (const auto& m : messages) {
        chat.push_back(llama_chat_message{m.first.c_str(), m.second.c_str()});
    }

    // Prefer the template embedded in the GGUF itself.
    const char* tmpl = llama_model_chat_template(AsModel(model_), nullptr);

    // Size probe, then real call.
    int32_t needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(),
                                               add_assistant_suffix, nullptr, 0);
    if (needed <= 0) return std::string();
    std::string out(static_cast<size_t>(needed), '\0');
    int32_t written = llama_chat_apply_template(tmpl, chat.data(), chat.size(),
                                                add_assistant_suffix, &out[0], needed + 1);
    if (written <= 0) return std::string();
    out.resize(static_cast<size_t>(written));
    return out;
}

void Session::ResetContext() {
    std::lock_guard<std::mutex> lock(mu_);
    if (ctx_ != nullptr) {
        llama_memory_clear(llama_get_memory(AsCtx(ctx_)), true);
    }
}

uint64_t Session::EstimateKvCacheBytes(int32_t n_layer, int32_t n_head_kv, int32_t n_embd,
                                       int32_t n_ctx) {
    if (n_layer <= 0 || n_head_kv <= 0 || n_embd <= 0 || n_ctx <= 0) return 0;
    const int32_t n_head = n_head_kv > 0 ? n_head_kv : 1;
    // head_dim is derived from the full embedding size and head count; when the
    // caller only knows n_embd we approximate head_dim = n_embd / n_head_kv,
    // which is what most decoder-only GGUF models use for the KV projection.
    const double head_dim = static_cast<double>(n_embd) / static_cast<double>(n_head);
    // K and V, 16-bit cache entries.
    const double bytes = 2.0 * n_layer * n_head * head_dim * n_ctx * 2.0;
    return static_cast<uint64_t>(bytes);
}

GenerateResult Session::Generate(const std::string& prompt,
                                 const GenerateConfig& cfg,
                                 const TokenCallback& on_token,
                                 const std::atomic<bool>* cancel,
                                 std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    if (model_ == nullptr || ctx_ == nullptr) {
        if (error) *error = "No model is loaded.";
        return GenerateResult::kNotLoaded;
    }
    if (prompt.empty()) {
        if (error) *error = "Prompt is empty.";
        return GenerateResult::kDecodeFailed;
    }

    cancel_requested_.store(false);
    llama_context* ctx = AsCtx(ctx_);
    const llama_vocab* vocab = AsVocab(vocab_);
    const uint32_t n_ctx = llama_n_ctx(ctx);
    const uint32_t n_batch = llama_n_batch(ctx);

    // Start every request from a clean KV cache. Conversation memory is
    // rebuilt from the prompt at the Kotlin layer, which keeps context usage
    // predictable on a phone with limited RAM.
    llama_memory_clear(llama_get_memory(ctx), true);

    const std::vector<int32_t> prompt_tokens = TokenizeWith(vocab, prompt, /*add_special=*/true);
    if (prompt_tokens.empty()) {
        if (error) *error = "Tokenization produced no tokens for this prompt.";
        return GenerateResult::kDecodeFailed;
    }

    // Reserve room for at least a few generated tokens.
    if (prompt_tokens.size() + 8 > static_cast<size_t>(n_ctx)) {
        if (error) {
            *error = "Prompt is " + std::to_string(prompt_tokens.size()) +
                     " tokens but the context is only " + std::to_string(n_ctx) +
                     ". Reduce the selected files or raise the context length.";
        }
        return GenerateResult::kContextFull;
    }

    // --- sampler chain -------------------------------------------------
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (smpl == nullptr) {
        if (error) *error = "Could not create sampler.";
        return GenerateResult::kInvalidSampler;
    }
    const uint32_t seed = cfg.seed != 0 ? cfg.seed : static_cast<uint32_t>(
        std::chrono::system_clock::now().time_since_epoch().count() & 0xFFFFFFFFu);
    if (cfg.repeat_penalty != 1.0f && cfg.repeat_last_n > 0) {
        // penalties(n_vocab, last_n, repeat, freq, present)
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), cfg.repeat_last_n, cfg.repeat_penalty, 0.0f, 0.0f));
    }
    if (cfg.top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(cfg.top_k));
    if (cfg.top_p > 0.0f && cfg.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(cfg.top_p, 1));
    }
    if (cfg.min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(cfg.min_p, 1));
    }
    if (cfg.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(cfg.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    }

    llama_batch batch = llama_batch_init(static_cast<int32_t>(std::max<uint32_t>(n_batch, 1)), 0, 1);

    // --- prompt processing ---------------------------------------------
    auto t_prompt_start = Clock::now();
    size_t n_past = 0;
    bool decode_failed = false;
    while (n_past < prompt_tokens.size()) {
        if (cancel_requested_.load() || (cancel && cancel->load())) {
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            if (error) error->clear();
            return GenerateResult::kCancelled;
        }
        batch.n_tokens = 0;
        const size_t chunk = std::min<size_t>(n_batch, prompt_tokens.size() - n_past);
        for (size_t k = 0; k < chunk; ++k) {
            const bool last = (n_past + k == prompt_tokens.size() - 1);
            BatchAdd(batch, static_cast<llama_token>(prompt_tokens[n_past + k]),
                     static_cast<llama_pos>(n_past + k), 0, last);
        }
        const int32_t rc = llama_decode(ctx, batch);
        if (rc != 0) {
            decode_failed = true;
            if (error) {
                *error = (rc == 1)
                    ? "No KV-cache slot available for the prompt. Lower the context length."
                    : "llama_decode failed while processing the prompt (code " +
                          std::to_string(rc) + ").";
            }
            break;
        }
        n_past += chunk;
    }
    const int64_t prompt_us = UsSince(t_prompt_start);
    if (decode_failed) {
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return (error && error->find("KV-cache") != std::string::npos)
                   ? GenerateResult::kOutOfMemory
                   : GenerateResult::kDecodeFailed;
    }

    // --- token loop ------------------------------------------------------
    auto t_gen_start = Clock::now();
    std::string emitted;
    int32_t n_generated = 0;
    GenerateResult result = GenerateResult::kMaxTokens;
    int32_t cur = static_cast<int32_t>(n_past);

    while (n_generated < cfg.max_tokens) {
        if (cancel_requested_.load() || (cancel && cancel->load())) {
            result = GenerateResult::kCancelled;
            break;
        }
        const llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            result = GenerateResult::kStopped;
            break;
        }
        llama_sampler_accept(smpl, id);

        char piece_buf[512];
        const int32_t pn = llama_token_to_piece(vocab, id, piece_buf, sizeof(piece_buf), 0, false);
        std::string piece;
        if (pn > 0) piece.assign(piece_buf, static_cast<size_t>(pn));
        emitted += piece;
        ++n_generated;

        if (on_token && !on_token(piece, static_cast<int32_t>(id), n_generated)) {
            result = GenerateResult::kStopped;
            break;
        }

        // Stop strings are checked on the accumulated text so multi-token
        // markers such as "\n\nUser:" still match.
        bool hit_stop = false;
        for (const auto& s : cfg.stop_strings) {
            if (!s.empty() && emitted.find(s) != std::string::npos) { hit_stop = true; break; }
        }
        if (hit_stop) { result = GenerateResult::kStopped; break; }

        if (static_cast<uint32_t>(cur + 1) >= n_ctx) {
            result = GenerateResult::kContextFull;
            break;
        }

        batch.n_tokens = 0;
        BatchAdd(batch, id, static_cast<llama_pos>(cur), 0, true);
        const int32_t rc = llama_decode(ctx, batch);
        if (rc != 0) {
            if (error) *error = "llama_decode failed during generation (code " +
                                std::to_string(rc) + ").";
            result = GenerateResult::kDecodeFailed;
            break;
        }
        ++cur;
    }

    const int64_t gen_us = UsSince(t_gen_start);
    llama_batch_free(batch);
    llama_sampler_free(smpl);

    // Update measured stats for the UI.
    stats_.last_prompt_tokens = static_cast<int32_t>(prompt_tokens.size());
    stats_.last_generated_tokens = n_generated;
    // Rates are computed from microseconds: on a fast device a short request can
    // complete in well under 1 ms, and integer millisecond division would report
    // a bogus 0.0 tokens/sec to the UI.
    stats_.last_prompt_ms = prompt_us / 1000;
    stats_.last_generate_ms = gen_us / 1000;
    stats_.last_prompt_tokens_per_sec =
        prompt_us > 0 ? (1e6 * static_cast<double>(prompt_tokens.size())) / prompt_us : 0.0;
    stats_.last_tokens_per_sec =
        gen_us > 0 ? (1e6 * static_cast<double>(n_generated)) / gen_us : 0.0;

    if (error) error->clear();
    return result;
}

}  // namespace llm
}  // namespace varun
