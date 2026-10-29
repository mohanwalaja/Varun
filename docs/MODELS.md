# Models

## No model is bundled

The APK contains **no AI model**. Nothing is downloaded automatically, and no
model is loaded without your explicit consent. You choose the file, you approve
the download, you press Load.

This matters for three reasons: a model is 400 MB–2 GB on a phone with limited
storage; model licenses differ per publisher and you must accept them yourself;
and an app that silently fetched a gigabyte over mobile data would be behaving
badly.

---

## Recommended model

| | |
|---|---|
| **Model** | Qwen2.5-Coder-1.5B-Instruct |
| **Quantization** | Q4_K_M |
| **File name** | `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf` |
| **Size** | ~1.14 GB |
| **Context** | 32,768 tokens (the app defaults to 2048 — see below) |
| **License** | Apache-2.0, redistribution permitted |
| **Source** | https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF |

This is the best balance for an 8 GB phone: small enough to load with headroom,
large enough to be genuinely useful on code, and Apache-2.0 so there are no
publisher terms to negotiate.

**Starting out?** Use **Qwen2.5-Coder-0.5B** (~400 MB) first. It loads fast and
tells you immediately whether the runtime works on your specific device, before
you spend 1.14 GB finding out.

---

## The in-app catalogue

Six entries, each with size, quantization, context, license, source URL and a
note about what it is actually good for.

| Model | Quant | Size | License | Notes |
|---|---|---|---|---|
| **Qwen2.5-Coder 1.5B** | Q4_K_M | ~1.14 GB | Apache-2.0 | **Recommended.** Best default for an 8 GB phone. |
| Qwen2.5-Coder 0.5B | Q4_K_M | ~400 MB | Apache-2.0 | Fastest and lightest. Use it to confirm the runtime works. |
| Qwen2.5-Coder 3B | Q4_K_M | ~1.9 GB | Apache-2.0 | Better reasoning, noticeably slower. Close every other app. |
| Gemma 3 1B | Q4_K_M | ~800 MB | **Gemma Terms of Use** | Good instruction following, weaker at code than Qwen at this size. |
| Llama 3.2 1B | Q4_K_M | ~750 MB | **Llama 3.2 Community License** | Popular and well supported; license terms you must accept yourself. |
| TinyLlama 1.1B Chat | Q4_K_M | ~670 MB | Apache-2.0 | 2k context only — too small for real file analysis. |

Sizes are the published GGUF file sizes at the time of writing. The app
**re-reads the real size after download** and recomputes the estimate from the
actual GGUF header, so a stale number here cannot cause a bad load.

---

## Licensing

### Runtime

| Component | License |
|---|---|
| llama.cpp | MIT |
| ggml | MIT |
| Jetpack Compose, AndroidX, Kotlin | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Varun Studio source | MIT (see [LICENSE](../LICENSE)) |

llama.cpp is **compiled from source at a pinned commit** during the build. No
prebuilt llama.cpp binary is stored in or redistributed with this repository.

### Models

**Models are licensed by their publishers and are NOT redistributed by this
project.** You download them yourself, from the publisher, and you accept their
terms.

- **Apache-2.0** — Qwen2.5-Coder (all sizes), TinyLlama. Permissive;
  redistribution permitted.
- **Gemma Terms of Use** — Gemma 3. *Not* an OSI open-source license. Read
  https://ai.google.dev/gemma/terms before sharing a Gemma file.
- **Llama 3.2 Community License** — *Not* OSI open source. Has usage terms and
  an acceptable-use policy. Read https://www.llama.com/llama3_2/license/.

The catalogue shows each model's license in the consent dialog **before** you
download, so you are not accepting terms sight-unseen.

---

## Getting a GGUF file yourself

If you would rather not use the catalogue, any GGUF works. Import it via
**Models → Import**.

Where small coder models are published:

- **Hugging Face** — `Qwen/Qwen2.5-Coder-*-GGUF`, `ggml-org/*-GGUF`,
  `bartowski/*-GGUF`, `TheBloke/*-GGUF`
- The in-app URL importer accepts only `https://` links to `.gguf` files on a
  small allowlist (`huggingface.co`, `hf.co`, `cdn-lfs.huggingface.co`,
  `github.com`, `objects.githubusercontent.com`)

That allowlist is deliberate: it keeps downloads to known publishers, and the
validator **rejects any URL that appears to carry credentials** — including
`https://user:pass@host/...` and anything with `token=`, `key=` or
`Authorization` in it — so a mistyped link cannot leak a secret into the
download log.

### Choosing a quantization

| Quant | Bits/weight | Trade-off |
|---|---|---|
| Q2_K | 2.9 | Smallest, noticeably degraded output |
| Q3_K_M | 3.9 | Usable when RAM is very tight |
| **Q4_K_M** | **4.85** | **The sweet spot — recommended** |
| Q5_K_M | 5.7 | Better quality, ~20% more RAM |
| Q6_K | 6.6 | Close to full quality |
| Q8_0 | 8.5 | Near-lossless; usually too big for a phone |
| F16 | 16.5 | Not practical on a phone |

Q4_K_M is the standard recommendation for mobile: the quality loss against Q8_0
is small for most coding tasks, and it roughly halves the memory footprint.

---

## Context length: why 32k becomes 2048

Qwen2.5-Coder supports 32,768 tokens. **The app defaults to 2048 anyway**, and
that is not an oversight.

The KV cache grows linearly with context length. For a 1.5B model at 28 layers:

| Context | KV cache |
|---|---|
| 512 | ~88 MB |
| 2048 | ~352 MB |
| 8192 | ~1.4 GB |
| 32768 | ~5.6 GB |

A 5.6 GB KV cache on an 8 GB phone will not fit, because Android does not give
one app 8 GB. Raising the context slider is the single easiest way to make the
app fail to load a model.

Settings → *Apply a conservative profile* picks a context length from the RAM
Android reports as available right now.

---

## How the memory estimate works

`ai/MemoryEstimator.kt` computes:

```
total = model file bytes
      + KV cache        (2 × layers × kv_heads × head_dim × ctx × 2 bytes)
      + compute buffers (batch × embd × 4 × 8, floored at 16 MB)
      + overhead        (48 MB base + 8 MB per inference thread)
```

The KV-cache formula **mirrors `Session::EstimateKvCacheBytes` in C++ exactly**,
and a unit test asserts that parity, so the number shown before you load and the
number the runtime computes internally cannot drift apart.

Before a model is loaded, parameter count is inferred from file size and the
quantization tag, and a typical decoder geometry is assumed. **After** loading,
the estimate is recomputed from the real GGUF header (layers, KV heads,
embedding size), which is why the figure can change once a model is live.

### The fit policy

| Rating | Meaning |
|---|---|
| **OK** | Total ≤ 60% of currently available RAM |
| **TIGHT** | Total ≤ 85%. Close other apps; expect pressure. |
| **UNLIKELY** | Total > 85%. Very likely to fail or thrash. **Never auto-loaded.** |

The 60/85 thresholds exist because Android kills processes that push the system
into memory pressure. Requiring meaningful headroom is what keeps the app alive
instead of being silently terminated mid-generation.

Estimation is against RAM **currently available**, not total device RAM, minus a
700 MB reserve for Android itself.

---

## What to expect from a 1.5B model

Be realistic. A 1.5B parameter model quantized to 4 bits, running on a phone
CPU with a 2048-token context, is **not** a desktop coding assistant.

It is reasonably good at:
- Explaining a short function you give it
- Spotting obvious bugs in a small file
- Suggesting a small refactor
- Summarising what a file does
- Drafting a boilerplate function or a test skeleton

It will struggle with:
- Whole-codebase reasoning (it can only see what fits in context)
- Subtle concurrency or architecture problems
- Anything requiring up-to-date library knowledge
- Long, multi-file changes

The app is built around those limits rather than pretending they are not there:
you select specific files, oversized files are truncated **with the truncation
disclosed to both you and the model**, dropped files are listed by name, and
every unverifiable claim is marked `[UNTESTED]`.

Speed has **not been measured on a physical device**. Do not assume desktop
performance; the app does not promise it.
