// Varun Studio - JNI bridge between Kotlin and the llama.cpp-backed engine.
//
// This file is deliberately THIN: all logic lives in llama_session.cpp so it
// can be compiled and tested on a desktop host. Only marshalling happens here.
//
// Thread-safety: every call runs on a Kotlin Dispatchers.IO worker. The JNIEnv
// is therefore always the caller's own; we never cache a JNIEnv across threads.
// The global callback references are ref-counted per call and always released.
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "llama_session.h"

#define JNI_CLASS "dev/varun/studio/ai/LlamaNative"

namespace {

using varun::llm::EngineConfig;
using varun::llm::EngineStats;
using varun::llm::GenerateConfig;
using varun::llm::GenerateResult;
using varun::llm::Session;

// The native handle is a heap Session*. A per-handle cancel flag lets Kotlin
// abort a running generation from a different thread.
struct NativeHandle {
    Session session;
    std::atomic<bool> cancel{false};
};

NativeHandle* AsHandle(jlong h) { return reinterpret_cast<NativeHandle*>(static_cast<uintptr_t>(h)); }

jstring ToJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

void ThrowRuntime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

// Cached method IDs for the token callback interface:
//   fun onToken(piece: String, tokenId: Int, nGenerated: Int): Boolean
struct TokenCallbackRef {
    jobject global = nullptr;
    jmethodID mid = nullptr;
};

TokenCallbackRef AcquireTokenCallback(JNIEnv* env, jobject cb) {
    TokenCallbackRef ref;
    if (cb == nullptr) return ref;
    jclass cls = env->GetObjectClass(cb);
    if (cls == nullptr) return ref;
    ref.mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;II)Z");
    env->DeleteLocalRef(cls);
    if (ref.mid == nullptr) {
        // getMethodID already raised NoSuchMethodError
        return ref;
    }
    ref.global = env->NewGlobalRef(cb);
    return ref;
}

void ReleaseTokenCallback(JNIEnv* env, TokenCallbackRef& ref) {
    if (ref.global != nullptr) {
        env->DeleteGlobalRef(ref.global);
        ref.global = nullptr;
    }
}

std::vector<std::string> JStringArrayToVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto* js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (js == nullptr) continue;
        const char* c = env->GetStringUTFChars(js, nullptr);
        if (c != nullptr) {
            out.emplace_back(c);
            env->ReleaseStringUTFChars(js, c);
        }
        env->DeleteLocalRef(js);
    }
    return out;
}

}  // namespace

extern "C" {

// ---------------------------------------------------------------------------
// Static / metadata
// ---------------------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeRuntimeVersion(JNIEnv* env, jclass) {
    return ToJString(env, Session::RuntimeVersion());
}

JNIEXPORT jboolean JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeSupportsMmap(JNIEnv*, jclass) {
    return Session::SupportsMmap() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeEstimateKvCacheBytes(JNIEnv*, jclass, jint n_layer,
                                                                jint n_head_kv, jint n_embd,
                                                                jint n_ctx) {
    return static_cast<jlong>(
        Session::EstimateKvCacheBytes(n_layer, n_head_kv, n_embd, n_ctx));
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeCreate(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(new NativeHandle()));
}

JNIEXPORT void JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) return;
    h->cancel.store(true);
    h->session.Unload();
    delete h;
}

JNIEXPORT jboolean JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeIsLoaded(JNIEnv*, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    return (h != nullptr && h->session.IsLoaded()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeLoad(JNIEnv* env, jclass, jlong handle,
                                                jstring model_path, jint n_ctx, jint n_batch,
                                                jint n_threads, jint n_threads_batch,
                                                jboolean use_mmap, jobject progress_cb) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) {
        ThrowRuntime(env, "Native handle is null");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return JNI_FALSE;

    EngineConfig cfg;
    cfg.model_path = path;
    cfg.n_ctx = n_ctx;
    cfg.n_batch = n_batch;
    cfg.n_threads = n_threads;
    cfg.n_threads_batch = n_threads_batch;
    cfg.use_mmap = (use_mmap == JNI_TRUE);
    env->ReleaseStringUTFChars(model_path, path);

    // Progress callback: fun onProgress(progress: Float)
    jobject progress_global = nullptr;
    jmethodID progress_mid = nullptr;
    if (progress_cb != nullptr) {
        jclass pcls = env->GetObjectClass(progress_cb);
        if (pcls != nullptr) {
            progress_mid = env->GetMethodID(pcls, "onProgress", "(F)V");
            env->DeleteLocalRef(pcls);
            if (progress_mid != nullptr) progress_global = env->NewGlobalRef(progress_cb);
        }
    }

    h->cancel.store(false);
    std::string err;
    const bool ok = h->session.Load(
        cfg,
        [&](float p) {
            if (progress_global == nullptr || progress_mid == nullptr) return;
            JNIEnv* cbenv = env;  // Load runs synchronously on this same thread
            cbenv->CallVoidMethod(progress_global, progress_mid, static_cast<jfloat>(p));
            if (cbenv->ExceptionCheck()) cbenv->ExceptionClear();
        },
        &err);

    if (progress_global != nullptr) env->DeleteGlobalRef(progress_global);

    if (!ok) {
        // Surface the reason to Kotlin through a pending exception so the
        // ViewModel can show an actionable message (OOM vs corrupt file).
        jclass ex = env->FindClass("java/lang/Exception");
        if (ex != nullptr) env->ThrowNew(ex, err.c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeUnload(JNIEnv*, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) return;
    h->cancel.store(true);
    h->session.Unload();
}

JNIEXPORT void JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeCancel(JNIEnv*, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) return;
    h->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeResetContext(JNIEnv*, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) return;
    h->session.ResetContext();
}

// ---------------------------------------------------------------------------
// Introspection
// ---------------------------------------------------------------------------

JNIEXPORT jlongArray JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeStatsLongs(JNIEnv* env, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    const EngineStats st = (h != nullptr) ? h->session.Stats() : EngineStats{};
    jlong values[13];
    values[0] = st.loaded ? 1 : 0;
    values[1] = st.n_vocab;
    values[2] = st.n_ctx;
    values[3] = st.n_ctx_train;
    values[4] = st.n_embd;
    values[5] = st.n_layer;
    values[6] = static_cast<jlong>(st.n_params);
    values[7] = static_cast<jlong>(st.model_bytes);
    values[8] = st.n_threads;
    values[9] = st.last_prompt_tokens;
    values[10] = st.last_generated_tokens;
    values[11] = st.last_prompt_ms;
    values[12] = st.last_generate_ms;
    jlongArray out = env->NewLongArray(13);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 13, values);
    return out;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeStatsDoubles(JNIEnv* env, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    const EngineStats st = (h != nullptr) ? h->session.Stats() : EngineStats{};
    jdouble values[2] = {st.last_prompt_tokens_per_sec, st.last_tokens_per_sec};
    jdoubleArray out = env->NewDoubleArray(2);
    if (out != nullptr) env->SetDoubleArrayRegion(out, 0, 2, values);
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeStatsStrings(JNIEnv* env, jclass, jlong handle) {
    NativeHandle* h = AsHandle(handle);
    const EngineStats st = (h != nullptr) ? h->session.Stats() : EngineStats{};
    jclass strcls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(2, strcls, nullptr);
    if (out != nullptr) {
        jstring a = ToJString(env, st.description);
        jstring b = ToJString(env, st.ftype);
        env->SetObjectArrayElement(out, 0, a);
        env->SetObjectArrayElement(out, 1, b);
        env->DeleteLocalRef(a);
        env->DeleteLocalRef(b);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeCountTokens(JNIEnv* env, jclass, jlong handle,
                                                       jstring text) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr || text == nullptr) return 0;
    const char* c = env->GetStringUTFChars(text, nullptr);
    if (c == nullptr) return 0;
    const std::string s(c);
    env->ReleaseStringUTFChars(text, c);
    return static_cast<jint>(h->session.Tokenize(s, false).size());
}

JNIEXPORT jstring JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeApplyChatTemplate(JNIEnv* env, jclass, jlong handle,
                                                             jobjectArray roles,
                                                             jobjectArray contents,
                                                             jboolean add_assistant) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) return ToJString(env, "");
    std::vector<std::string> r = JStringArrayToVector(env, roles);
    std::vector<std::string> c = JStringArrayToVector(env, contents);
    std::vector<std::pair<std::string, std::string>> msgs;
    const size_t n = std::min(r.size(), c.size());
    msgs.reserve(n);
    for (size_t i = 0; i < n; ++i) msgs.emplace_back(r[i], c[i]);
    const std::string out =
        h->session.ApplyChatTemplate(msgs, add_assistant == JNI_TRUE);
    return ToJString(env, out);
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeGenerate(JNIEnv* env, jclass, jlong handle,
                                                    jstring prompt, jint max_tokens,
                                                    jfloat temperature, jint top_k, jfloat top_p,
                                                    jfloat min_p, jfloat repeat_penalty,
                                                    jint repeat_last_n, jlong seed,
                                                    jobjectArray stop_strings,
                                                    jboolean apply_chat_template,
                                                    jobject token_cb) {
    NativeHandle* h = AsHandle(handle);
    if (h == nullptr) return static_cast<jint>(GenerateResult::kNotLoaded);

    const char* pc = env->GetStringUTFChars(prompt, nullptr);
    if (pc == nullptr) return static_cast<jint>(GenerateResult::kDecodeFailed);
    std::string prompt_str(pc);
    env->ReleaseStringUTFChars(prompt, pc);

    GenerateConfig cfg;
    cfg.max_tokens = max_tokens;
    cfg.temperature = temperature;
    cfg.top_k = top_k;
    cfg.top_p = top_p;
    cfg.min_p = min_p;
    cfg.repeat_penalty = repeat_penalty;
    cfg.repeat_last_n = repeat_last_n;
    cfg.seed = static_cast<uint32_t>(seed);
    cfg.stop_strings = JStringArrayToVector(env, stop_strings);
    cfg.apply_chat_template = (apply_chat_template == JNI_TRUE);

    // If requested, wrap the raw prompt as a single user turn using the model's
    // own template before tokenizing.
    if (cfg.apply_chat_template) {
        std::vector<std::pair<std::string, std::string>> msgs = {{"user", prompt_str}};
        const std::string rendered = h->session.ApplyChatTemplate(msgs, true);
        if (!rendered.empty()) prompt_str = rendered;
    }

    TokenCallbackRef cbref = AcquireTokenCallback(env, token_cb);
    if (token_cb != nullptr && (cbref.global == nullptr || cbref.mid == nullptr)) {
        ReleaseTokenCallback(env, cbref);
        return static_cast<jint>(GenerateResult::kInvalidSampler);
    }

    h->cancel.store(false);
    std::string err;

    const GenerateResult result = h->session.Generate(
        prompt_str, cfg,
        [&](const std::string& piece, int32_t token_id, int32_t n_generated) -> bool {
            if (cbref.global == nullptr || cbref.mid == nullptr) return true;
            jstring jpiece = ToJString(env, piece);
            const jboolean keep = env->CallBooleanMethod(
                cbref.global, cbref.mid, jpiece, static_cast<jint>(token_id),
                static_cast<jint>(n_generated));
            if (jpiece != nullptr) env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) {
                // A Kotlin exception in the callback must not unwind through C++.
                env->ExceptionClear();
                return false;
            }
            return keep == JNI_TRUE;
        },
        &h->cancel, &err);

    ReleaseTokenCallback(env, cbref);

    if (!err.empty()) {
        // Attach the message as a pending exception; Kotlin converts it to a
        // user-visible error rather than a crash.
        jclass ex = env->FindClass("java/lang/Exception");
        if (ex != nullptr) env->ThrowNew(ex, err.c_str());
    }
    return static_cast<jint>(result);
}

}  // extern "C"
