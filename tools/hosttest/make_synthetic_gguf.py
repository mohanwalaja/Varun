#!/usr/bin/env python3
"""Synthesize a tiny, VALID GGUF model with random weights.

Purpose: verify the on-device inference pipeline (GGUF load -> tokenize ->
decode -> sample -> detokenize -> stream) end-to-end on a machine where no
real pretrained model can be downloaded.

The weights are random, so generated text is gibberish BY DESIGN. What is
being verified is the engine plumbing, not model quality.
"""
import sys
import numpy as np
from gguf import GGUFWriter

N_VOCAB = 320
N_EMBD = 64
N_HEAD = 4
N_HEAD_KV = 4
N_FF = 128
N_LAYER = 2
N_CTX = 256


def build_vocab():
    tokens, scores, ttypes = [], [], []
    # SentencePiece-style: unk, bos, eos first
    tokens += ["<unk>", "<s>", "</s>"]
    scores += [0.0, 0.0, 0.0]
    ttypes += [2, 3, 3]  # UNKNOWN, CONTROL, CONTROL
    # byte-fallback tokens
    for b in range(256):
        tokens.append("<0x%02X>" % b)
        scores.append(-1000.0)
        ttypes.append(6)  # BYTE
    # a few real words so tokenization of a prompt is non-trivial
    for w in ["▁hello", "▁code", "▁function", "▁return", "▁def",
              "▁if", "▁x", "▁=", "▁1", "▁2", "▁print", "▁the", "▁bug",
              "▁int", "▁void", "▁class", "▁test", "▁fix", "▁import",
              "▁null", "▁true", "▁false", "▁for", "▁while", "▁this",
              "▁self", "▁value", "▁error", "▁file", "▁line", "▁repo",
              "▁git", "▁commit", "▁branch", "▁diff", "▁patch", "▁apply",
              "▁review", "▁security", "▁token", "▁model", "▁local",
              "▁device", "▁android", "▁kotlin", "▁java", "▁python",
              "▁cpp", "▁rust", "▁go", "▁js", "▁ts", "▁sql", "▁json",
              "▁yaml", "▁toml", "▁xml", "▁html", "▁css"]:
        tokens.append(w)
        scores.append(-float(len(tokens)) * 0.01)
        ttypes.append(1)  # NORMAL
    # pad to N_VOCAB
    while len(tokens) < N_VOCAB:
        tokens.append("<pad%d>" % len(tokens))
        scores.append(-100.0)
        ttypes.append(3)  # CONTROL
    seen, ut, us, uy = set(), [], [], []
    for tok, sc, ty in zip(tokens, scores, ttypes):
        if tok in seen:
            continue
        seen.add(tok)
        ut.append(tok); us.append(sc); uy.append(ty)
    while len(ut) < N_VOCAB:
        cand = "<pad%d>" % len(ut)
        if cand in seen:
            cand = "<pad%d_%d>" % (len(ut), len(seen))
        seen.add(cand); ut.append(cand); us.append(-100.0); uy.append(3)
    return ut[:N_VOCAB], us[:N_VOCAB], uy[:N_VOCAB]


def main(out_path):
    rng = np.random.default_rng(1234)
    w = GGUFWriter(out_path, arch="llama")

    w.add_name("VarunSyntheticTiny-2L-64D")
    w.add_context_length(N_CTX)
    w.add_embedding_length(N_EMBD)
    w.add_block_count(N_LAYER)
    w.add_feed_forward_length(N_FF)
    w.add_head_count(N_HEAD)
    w.add_head_count_kv(N_HEAD_KV)
    w.add_rope_dimension_count(N_EMBD // N_HEAD)
    w.add_layer_norm_rms_eps(1e-5)

    tokens, scores, ttypes = build_vocab()
    w.add_tokenizer_model("llama")
    w.add_token_list(tokens)
    w.add_token_scores([float(x) for x in scores])
    w.add_token_types([int(x) for x in ttypes])
    w.add_bos_token_id(1)
    w.add_eos_token_id(2)
    w.add_unk_token_id(0)
    w.add_pad_token_id(0)
    w.add_add_bos_token(True)
    w.add_add_eos_token(False)

    def t(name, shape, scale=0.05):
        arr = (rng.standard_normal(shape).astype(np.float32) * scale)
        w.add_tensor(name, arr)

    t("token_embd.weight", (N_VOCAB, N_EMBD), 0.02)
    t("output.weight", (N_VOCAB, N_EMBD), 0.02)
    t("output_norm.weight", (N_EMBD,), 1.0)

    for i in range(N_LAYER):
        p = "blk.%d." % i
        t(p + "attn_norm.weight", (N_EMBD,), 1.0)
        t(p + "attn_q.weight", (N_EMBD, N_EMBD))
        t(p + "attn_k.weight", (N_EMBD, N_EMBD))
        t(p + "attn_v.weight", (N_EMBD, N_EMBD))
        t(p + "attn_output.weight", (N_EMBD, N_EMBD))
        t(p + "ffn_norm.weight", (N_EMBD,), 1.0)
        t(p + "ffn_gate.weight", (N_FF, N_EMBD))
        t(p + "ffn_down.weight", (N_EMBD, N_FF))
        t(p + "ffn_up.weight", (N_FF, N_EMBD))

    w.write_header_to_file()
    w.write_kv_data_to_file()
    w.write_tensors_to_file()
    w.close()
    print("wrote", out_path)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "synthetic-tiny.gguf")
