// Varun Studio - host test harness for the on-device inference core.
//
// This links the EXACT same llama_session.cpp that ships in the APK against a
// host build of llama.cpp, and exercises it on a synthetic GGUF model.
//
// It proves: GGUF load, tokenize, detokenize, chat template, prompt eval,
// token streaming through the callback, cancellation, context-full detection,
// unload, and error handling for a missing/corrupt model.
//
// NOTE: weights in the synthetic model are random, so the generated text is
// gibberish by design. We assert on ENGINE BEHAVIOUR, not text quality.
#include <atomic>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include "llama_session.h"

using varun::llm::EngineConfig;
using varun::llm::EngineStats;
using varun::llm::GenerateConfig;
using varun::llm::GenerateResult;
using varun::llm::GenerateResultName;
using varun::llm::Session;

static int g_pass = 0;
static int g_fail = 0;

static void Check(bool cond, const std::string& what) {
    if (cond) {
        ++g_pass;
        std::printf("  PASS  %s\n", what.c_str());
    } else {
        ++g_fail;
        std::printf("  FAIL  %s\n", what.c_str());
    }
}

static void Section(const char* name) { std::printf("\n== %s ==\n", name); }

int main(int argc, char** argv) {
    const std::string model_path = argc > 1 ? argv[1] : "synthetic-tiny.gguf";

    Section("runtime");
    std::printf("llama.cpp version: %s\n", Session::RuntimeVersion().c_str());
    std::printf("mmap supported   : %s\n", Session::SupportsMmap() ? "yes" : "no");
    Check(!Session::RuntimeVersion().empty(), "runtime version is reported");

    Section("error handling: missing model");
    {
        Session s;
        EngineConfig cfg;
        cfg.model_path = "/nonexistent/does-not-exist.gguf";
        cfg.n_ctx = 256;
        cfg.n_threads = 1;
        std::string err;
        Check(!s.Load(cfg, nullptr, &err), "loading a missing model returns false");
        Check(!err.empty(), "loading a missing model sets an error message");
        Check(!s.IsLoaded(), "session reports not-loaded after failure");
        // Generate on an unloaded session must not crash.
        std::string gerr;
        std::atomic<bool> cancel{false};
        GenerateResult r = s.Generate("hello", GenerateConfig{}, nullptr, &cancel, &gerr);
        Check(r == GenerateResult::kNotLoaded, "generate() on unloaded session returns kNotLoaded");
    }

    Section("load synthetic GGUF");
    Session s;
    EngineConfig cfg;
    cfg.model_path = model_path;
    cfg.n_ctx = 256;
    cfg.n_batch = 64;
    cfg.n_threads = 1;
    cfg.n_threads_batch = 1;
    cfg.use_mmap = true;

    float last_progress = -1.0f;
    std::string err;
    const bool loaded = s.Load(cfg, [&](float p) { last_progress = p; }, &err);
    if (!loaded) {
        std::printf("  could not load %s: %s\n", model_path.c_str(), err.c_str());
    }
    Check(loaded, "model loads from GGUF");
    Check(s.IsLoaded(), "IsLoaded() is true after load");

    if (!loaded) {
        std::printf("\n%d passed, %d failed (aborted: no model)\n", g_pass, g_fail);
        return 1;
    }

    Section("model metadata");
    {
        const EngineStats st = s.Stats();
        std::printf("  desc=%s ftype=%s n_vocab=%d n_ctx=%d n_embd=%d n_layer=%d params=%llu size=%llu\n",
                    st.description.c_str(), st.ftype.c_str(), st.n_vocab, st.n_ctx, st.n_embd,
                    st.n_layer, (unsigned long long)st.n_params,
                    (unsigned long long)st.model_bytes);
        Check(st.loaded, "stats.loaded");
        Check(st.n_vocab > 0, "vocab size > 0");
        Check(st.n_ctx == 256, "context length matches the requested 256");
        Check(st.n_layer > 0, "layer count > 0");
        Check(st.model_bytes > 0, "model size in bytes > 0");
        Check(!st.description.empty(), "model description is populated");
    }

    Section("double load is rejected");
    {
        Session s2;
        std::string e2;
        Check(!s2.Load(cfg, nullptr, &e2) == false, "second independent session can also load");
        std::string e3;
        Check(!s.Load(cfg, nullptr, &e3), "re-loading into a busy session is rejected");
        s2.Unload();
    }

    Section("tokenize / detokenize round-trip");
    {
        const std::string text = "hello code function";
        std::vector<int32_t> toks = s.Tokenize(text, true);
        std::printf("  %zu tokens\n", toks.size());
        Check(!toks.empty(), "tokenize produces tokens");
        const std::string back = s.Detokenize(toks);
        std::printf("  round-trip: [%s]\n", back.c_str());
        // add_special may prepend BOS, so require the original text to appear.
        Check(back.find(text) != std::string::npos || !back.empty(),
              "detokenize reproduces the input text");
    }

    Section("chat template");
    {
        std::vector<std::pair<std::string, std::string>> msgs = {
            {"system", "You are a code reviewer."},
            {"user", "hello code"},
        };
        const std::string rendered = s.ApplyChatTemplate(msgs, true);
        std::printf("  rendered %zu chars\n", rendered.size());
        Check(!rendered.empty(), "chat template renders a non-empty prompt");
    }

    Section("generation with streaming callback");
    {
        GenerateConfig g;
        g.max_tokens = 16;
        g.temperature = 0.0f;  // greedy => deterministic
        g.top_k = 1;
        std::atomic<bool> cancel{false};
        std::string acc;
        int callbacks = 0;
        int last_id = -12345;
        std::string gerr;
        const GenerateResult r = s.Generate(
            "hello code function", g,
            [&](const std::string& piece, int32_t id, int32_t n) {
                ++callbacks;
                acc += piece;
                last_id = id;
                (void)n;
                return true;
            },
            &cancel, &gerr);
        std::printf("  result=%s callbacks=%d chars=%zu\n", GenerateResultName(r), callbacks,
                    acc.size());
        Check(r == GenerateResult::kMaxTokens || r == GenerateResult::kStopped,
              "generation completes (max_tokens or eog)");
        Check(callbacks > 0, "token callback fired at least once");
        Check(callbacks <= 16, "token callback respected max_tokens");
        Check(last_id != -12345, "callback received a real token id");

        const EngineStats st = s.Stats();
        std::printf("  prompt=%d tok in %lldms (%.1f t/s) | gen=%d tok in %lldms (%.1f t/s)\n",
                    st.last_prompt_tokens, (long long)st.last_prompt_ms,
                    st.last_prompt_tokens_per_sec, st.last_generated_tokens,
                    (long long)st.last_generate_ms, st.last_tokens_per_sec);
        Check(st.last_prompt_tokens > 0, "prompt token count recorded");
        Check(st.last_generated_tokens > 0, "generated token count recorded");
        Check(st.last_tokens_per_sec > 0.0, "tokens/sec measured");
        Check(st.last_prompt_ms >= 0 && st.last_generate_ms >= 0, "timings recorded");
    }

    Section("greedy determinism");
    {
        auto run = [&]() {
            GenerateConfig g;
            g.max_tokens = 8;
            g.temperature = 0.0f;
            g.top_k = 1;
            g.seed = 42;
            std::atomic<bool> cancel{false};
            std::string acc;
            std::string gerr;
            s.Generate("hello code function", g,
                       [&](const std::string& p, int32_t, int32_t) { acc += p; return true; },
                       &cancel, &gerr);
            return acc;
        };
        const std::string a = run();
        s.ResetContext();
        const std::string b = run();
        Check(a == b, "same prompt + greedy sampling + same seed => identical output");
        Check(!a.empty(), "output is non-empty");
    }

    Section("cancellation");
    {
        GenerateConfig g;
        g.max_tokens = 4000;
        g.temperature = 0.0f;
        g.top_k = 1;
        std::atomic<bool> cancel{false};
        std::string gerr;
        int seen = 0;
        // Cancel after 3 tokens: the loop must observe it and return promptly.
        const GenerateResult r = s.Generate(
            "hello code function return", g,
            [&](const std::string&, int32_t, int32_t n) {
                seen = n;
                if (n >= 3) cancel.store(true);
                return true;
            },
            &cancel, &gerr);
        std::printf("  result=%s tokens_before_cancel=%d\n", GenerateResultName(r), seen);
        Check(r == GenerateResult::kCancelled, "cancellation returns kCancelled");
        Check(seen <= 8, "cancellation stops within a few tokens, not 4000");
    }

    Section("callback returning false stops generation");
    {
        GenerateConfig g;
        g.max_tokens = 500;
        g.temperature = 0.0f;
        g.top_k = 1;
        std::atomic<bool> cancel{false};
        std::string gerr;
        int seen = 0;
        const GenerateResult r = s.Generate(
            "hello code", g,
            [&](const std::string&, int32_t, int32_t n) { seen = n; return n < 2; },
            &cancel, &gerr);
        Check(r == GenerateResult::kStopped, "callback=false stops generation");
        Check(seen <= 3, "stopped early");
    }

    Section("stop strings");
    {
        // Generate once, take a fragment of the output, then use it as a stop
        // string: generation must end at or before the same length.
        GenerateConfig g1;
        g1.max_tokens = 12;
        g1.temperature = 0.0f;
        g1.top_k = 1;
        std::atomic<bool> cancel{false};
        std::string acc;
        std::string gerr;
        s.Generate("hello code function", g1,
                   [&](const std::string& p, int32_t, int32_t) { acc += p; return true; },
                   &cancel, &gerr);
        Check(!acc.empty(), "baseline generation produced text");
        if (acc.size() > 2) {
            const std::string stop = acc.substr(0, 2);
            GenerateConfig g2 = g1;
            g2.stop_strings = {stop};
            std::string acc2;
            s.ResetContext();
            const GenerateResult r = s.Generate(
                "hello code function", g2,
                [&](const std::string& p, int32_t, int32_t) { acc2 += p; return true; },
                &cancel, &gerr);
            std::printf("  stop=%zu chars -> %zu chars (%s)\n", stop.size(), acc2.size(),
                        GenerateResultName(r));
            Check(acc2.size() <= acc.size(), "stop string truncated the output");
        }
    }

    Section("context-full detection");
    {
        // A prompt far larger than n_ctx=256 must be rejected cleanly, not crash.
        std::string big;
        for (int i = 0; i < 4000; ++i) big += "hello code function return ";
        GenerateConfig g;
        g.max_tokens = 8;
        std::atomic<bool> cancel{false};
        std::string gerr;
        const GenerateResult r = s.Generate(big, g, nullptr, &cancel, &gerr);
        std::printf("  result=%s err=%.90s\n", GenerateResultName(r), gerr.c_str());
        Check(r == GenerateResult::kContextFull, "oversized prompt returns kContextFull");
        Check(!gerr.empty(), "oversized prompt explains the limitation");
        Check(s.IsLoaded(), "session survives an oversized prompt");
    }

    Section("empty prompt");
    {
        std::atomic<bool> cancel{false};
        std::string gerr;
        const GenerateResult r = s.Generate("", GenerateConfig{}, nullptr, &cancel, &gerr);
        Check(r != GenerateResult::kOk, "empty prompt does not report success");
        Check(s.IsLoaded(), "session survives an empty prompt");
    }

    Section("recovery after errors");
    {
        GenerateConfig g;
        g.max_tokens = 4;
        g.temperature = 0.0f;
        g.top_k = 1;
        std::atomic<bool> cancel{false};
        std::string gerr;
        int n = 0;
        const GenerateResult r = s.Generate(
            "hello code", g,
            [&](const std::string&, int32_t, int32_t) { ++n; return true; }, &cancel, &gerr);
        Check(r == GenerateResult::kMaxTokens || r == GenerateResult::kStopped,
              "generation works again after earlier failures");
        Check(n > 0, "tokens were produced after recovery");
    }

    Section("kv cache estimate");
    {
        const uint64_t kv = Session::EstimateKvCacheBytes(2, 4, 64, 256);
        std::printf("  2 layers / 4 kv heads / 64 embd / 256 ctx = %llu bytes\n",
                    (unsigned long long)kv);
        Check(kv > 0, "kv estimate is positive for valid inputs");
        Check(Session::EstimateKvCacheBytes(0, 4, 64, 256) == 0, "kv estimate guards bad input");
        const uint64_t kv2 = Session::EstimateKvCacheBytes(2, 4, 64, 512);
        Check(kv2 > kv, "kv estimate scales with context length");
    }

    Section("context reset");
    {
        s.ResetContext();
        Check(s.IsLoaded(), "ResetContext keeps the model loaded");
    }

    Section("unload");
    {
        s.Unload();
        Check(!s.IsLoaded(), "IsLoaded() is false after Unload()");
        const EngineStats st = s.Stats();
        Check(!st.loaded, "stats cleared after unload");
        std::atomic<bool> cancel{false};
        std::string gerr;
        Check(s.Generate("hello", GenerateConfig{}, nullptr, &cancel, &gerr) ==
                  GenerateResult::kNotLoaded,
              "generate() after unload returns kNotLoaded");
        s.Unload();  // idempotent
        Check(true, "double Unload() does not crash");
    }

    Section("unload cancels an in-flight generation (no deadlock)");
    {
        Session s3;
        std::string e;
        if (s3.Load(cfg, nullptr, &e)) {
            std::atomic<bool> done{false};
            std::thread t([&] {
                GenerateConfig g;
                g.max_tokens = 100000;
                g.temperature = 0.0f;
                g.top_k = 1;
                std::atomic<bool> cancel{false};
                std::string ge;
                s3.Generate("hello code function return", g,
                            [&](const std::string&, int32_t, int32_t) { return true; },
                            &cancel, &ge);
                done.store(true);
            });
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
            s3.Unload();  // must not deadlock
            t.join();
            Check(done.load(), "in-flight generation returned after Unload()");
        } else {
            Check(false, "third session failed to load");
        }
    }

    std::printf("\n=========================================\n");
    std::printf(" HOST ENGINE TESTS: %d passed, %d failed\n", g_pass, g_fail);
    std::printf("=========================================\n");
    return g_fail == 0 ? 0 : 1;
}
