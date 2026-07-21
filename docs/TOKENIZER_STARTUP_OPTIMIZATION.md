# Tokenizer Startup Optimization (Phase 11B)

Phase 11A found that half of engine startup was a JSON parser, not the neural network. This phase
fixes exactly that, and nothing else.

**Result: tokenizer load 12,675 ms → 4,188 ms (−67%). Engine ready 24,662 ms → 16,584 ms (−32.8%).
Translation output byte-identical, runtime and memory unchanged.**

All measurements on the **SM-M315F** (Exynos 9611, 4×A73 + 4×A53, Armv8.0-A, Android 12), debug build.

---

## 1. Root cause

`Tokenizer.parseFlatIntDict` consumes the vocabulary **one character per `Reader.read()`**. The
production reader was a bare `InputStreamReader`, so every one of those calls crossed into
`StreamDecoder`. On `dict.TGT.json` — 3,390,440 bytes, 122,672 entries — that is ~3.4 M decoder calls.

Phase 11A isolated the cost by running the **same parser** over the **same file** with only the reader
changed (`StartupProbeTest.probeTokenizerLoad`):

| Path | ms |
|---|---|
| Raw byte read, no decode, no parse (I/O floor) | **18** |
| `parseFlatIntDict(InputStreamReader(...))` — production | **9,951** |
| `parseFlatIntDict(BufferedReader(..., 64 KB))` | **1,082** |

I/O was 0.2% of the stage. Parse logic was ~11%. The remaining ~89% was per-call reader overhead —
work that produces no output at all.

---

## 2. Implementation

One reader, introduced in one place, used by both dictionary parses:

```kotlin
private fun bufferedUtf8(input: InputStream): Reader =
    BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_BYTES)

private const val BUFFER_BYTES = 1 shl 16   // 64 KB
```

```diff
- val src = context.assets.open(srcDict).use { parseFlatIntDict(InputStreamReader(it, Charsets.UTF_8)) }
+ val src = context.assets.open(srcDict).use { parseFlatIntDict(bufferedUtf8(it)) }
- val tgt = context.assets.open(tgtDict).use { parseFlatIntDict(InputStreamReader(it, Charsets.UTF_8)) }
+ val tgt = context.assets.open(tgtDict).use { parseFlatIntDict(bufferedUtf8(it)) }
```

That is the entire production change: **4 lines touched, 2 of them the call sites.**

What was *not* touched, by design and by the phase brief: the tokenizer format, the vocabulary files,
`parseFlatIntDict` itself, the parsing algorithm, `MtEngine`, the decoder, the KV cache, ONNX Runtime,
session creation, quantization, model assets, and the adaptive Arm runtime.

**Why this cannot change behaviour.** A buffer changes *when* bytes are fetched from the underlying
stream, never how they are decoded or interpreted. Same `Charsets.UTF_8`, same character sequence in
the same order, same parser consuming it, same whitespace handling, same error propagation (both
readers surface `IOException` identically, and `use {}` closes the chain either way). 64 KB is one
order above the JDK default of 8 KB; larger buffers stop paying, because the cost being removed is
per-*call*, not per-refill.

---

## 3. Correctness validation

| Check | Method | Result |
|---|---|---|
| Identical vocabulary size | Parse `dict.TGT.json` both ways in one process, compare | **122,672 = 122,672** ✓ |
| Identical token IDs | Full `Map<String, Int>` equality between the two parses | **`identical=true`** ✓ |
| Identical special tokens | Covered by map equality (`<unk>`, ids 0–3, the `▁`-prefixed pieces) | ✓ |
| Identical merge count | **N/A** — this tokenizer is dictionary-driven; there is no merges file. `.model` protobufs are never opened (see `Tokenizer` class doc) | N/A |
| Identical translation output | `MtEngineInstrumentedTest` parity assertions on device | `'Water.' => 'पानी ।' \| 'पानी ।'`, `'Hello, how are you?' => 'हैलो , आप कैसे हैं ?'` ✓ |
| Identical benchmark output | 3 sentences × 30 runs after the change | All three byte-identical to every phase since 6D ✓ |
| Existing tokenizer tests | `./gradlew testDebugUnitTest` | **20/20 pass** (TokenizerTest 6, DecoderTest 7, MetricsTest 6, Example 1) ✓ |
| Instrumented tests | `StartupProbeTest#probeTokenizerLoad` + `MtEngineInstrumentedTest` | **3/3 pass** ✓ |

The equality assertion is now permanent: `probeTokenizerLoad` fails the build if a buffered and an
unbuffered parse of the real vocabulary ever disagree.

---

## 4. Before / after benchmark

### 4.1 Method

20 warm startups (`am force-stop` → `am start` → capture the `engine_init` run) and 3 cold startups
(extracted `.onnx` files deleted via `run-as` first, so extraction is re-paid while onboarding state
is preserved). Phase 11A's "before" figures come from that phase's runs (n = 2 warm, n = 1 cold) —
the asymmetry in sample size is stated rather than hidden.

### 4.2 Warm start — stage breakdown (n = 20)

| Stage | Before (11A) | After median | After p95 | After stdev | Δ |
|---|---|---|---|---|---|
| `tokenizer:src_dict` | 2,861.2 | **2,381.3** | 2,543.9 | 193.9 | −16.8% |
| `tokenizer:tgt_dict` | 9,204.7 | **1,160.3** | 1,239.4 | 89.9 | **−87.4% (7.9×)** |
| `tokenizer:reverse_index` | 609.2 | 579.1 | 648.2 | 67.2 | −4.9% |
| **tokenizer total** | **12,675** | **4,188.2** | 4,310.2 | 267.2 | **−67.0% (3.03×)** |
| `session:encoder` | 2,494.5 | 2,557.7 | 2,616.3 | 66.2 | +2.5% |
| `session:decoder_init` | 4,859.5 | 5,047.5 | 5,134.9 | 113.9 | +3.9% |
| `session:decoder_step` | 4,509.9 | 4,705.2 | 4,794.2 | 78.9 | +4.3% |
| **ONNX session total** | **11,864** | **12,311.3** | 12,532.5 | 197.0 | +3.8% (noise) |
| `verify:encoder` (incl. ORT native load) | 114.3 | 116.0 | 120.0 | 48.4 | — |
| `cache_contract` | 7.9 | 7.3 | 9.9 | 1.3 | — |
| **engine_init total** | **24,662** | **16,584.3** | **16,831.3** | **319.8** | **−32.8% (1.49×)** |

Range across the 20 runs: 15,783–16,852 ms. Standard deviation is 1.9% of the median.

The session stages sit ~3.8% above their Phase 11A values. Those are single-run "before" figures
against a 20-run median; the sessions were not touched, and the direction is consistent with normal
run-to-run drift on this device (Phase 10 saw ±0.16% on translation medians across days, but session
creation was only ever sampled twice).

### 4.3 Cold start (n = 3)

| | Before (11A) | After |
|---|---|---|
| Total | 27,331 | **17,627** (runs: 18,236 / 17,627 / 17,506) |
| — tokenizer | 12,727 | 3,269–3,502 |
| — extraction | 1,806 | 1,794–2,794 |
| — sessions | 12,661 | 12,042–12,294 |

**−9,704 ms (−35.5%).** Extraction is unchanged, as expected — it was never touched.

### 4.4 Controlled in-process A/B

Both readers, same file, same process, back to back (`probeTokenizerLoad`, two separate runs):

| Run | Unbuffered | Buffered | Ratio |
|---|---|---|---|
| Phase 11A | 9,951 ms | 1,082 ms | 9.2× |
| Phase 11B | 7,193 ms | 787 ms | 9.1× |

This is the cleanest evidence in the report: it controls for device state, thermal condition and page
cache, and it is reproducible on demand.

### 4.5 No regression

| Metric | Before | After | Verdict |
|---|---|---|---|
| "Water." (2 tok) median | 163.1 ms | **163.0 ms** | unchanged |
| "Hello, how are you?" (6 tok) | 364.5 ms | **362.4 ms** | unchanged (−0.6%) |
| "The weather…" (12 tok) | 667.9 ms | **669.9 ms** | unchanged (+0.3%) |
| Translation outputs | 3 sentences | identical | ✓ |
| Process memory (PSS after 90 translations) | 630 MB | **629 MB** | unchanged |

Runtime was never expected to move — `encode()`/`decode()` were not touched — and it did not.

---

## 5. Overall startup impact

Warm start, process fork → engine ready:

| | Before | After |
|---|---|---|
| fork → `Application.onCreate` | 360 ms | 360 ms |
| `onCreate` → engine construction starts | 834 ms | 834 ms |
| **engine construction** | **24,662 ms** | **16,584 ms** |
| **total** | **25,856 ms** | **≈17,778 ms** |

```
BEFORE  ████████████████████████ tokenizer 12,675 (51.4%) ███████████████████████ sessions 11,864 (48.1%)
AFTER   ████████ tokenizer 4,188 (25.3%)                  ████████████████████████ sessions 12,311 (74.2%)
        └────────────────────── 24,662 ms ──────────────┘ └──────── 16,584 ms ────────┘
```

The tokenizer has gone from the **largest** contributor to a distant second. Cold start improves from
27.3 s to 17.6 s; a user on first install waits ~9.7 s less, and on every later launch ~8.1 s less.

---

## 6. Remaining startup bottlenecks

Ranked by measured size, with the evidence already gathered in Phase 11A. **None of these is
implemented — this phase stops here by instruction.**

| # | Bottleneck | Now | Evidence | Potential |
|---|---|---|---|---|
| 1 | **ONNX session creation** — 74.2% of engine_init | 12,311 ms | Disk read is ~3%; `NO_OPT` is 52–61% faster than the default `ALL_OPT`, so graph optimisation passes dominate | Pre-optimise graphs offline and load with `NO_OPT`: ~6 s |
| 2 | **Sequential session loading** | included above | Probe: serial 12,285 ms vs 6,258 ms on three threads | ~5.9 s (**explicitly deferred — Phase 11B must not start this**) |
| 3 | **First-parse JIT warm-up in the tokenizer** | ~2,381 ms | After buffering, `dict.SRC.json` parses at 0.27 MB/s while `dict.TGT.json` — same code, same process, moments later — parses at 2.92 MB/s. A **10.8× throughput gap between two executions of the same loop** is cold-code, not I/O | Baseline profiles (ART AOT), or a binary vocabulary format that does far less interpreted work |
| 4 | **Reverse-index construction** | 579 ms | Builds a 122,672-entry inverted map | Could be built lazily; only `decode()` needs it |
| 5 | **Asset extraction** (cold only) | ~1,800 ms | 472 MB at ~262 MB/s, once per install | Load models from a memory-mapped asset instead of copying |
| 6 | **Pre-engine cost** | 1,194 ms | fork → onCreate 360 ms, then Activity + ViewModel + dispatch | Little to win; the screen is already interactive at ~1.4 s |

The honest summary: the remaining 16.6 s is now **74% ONNX Runtime session creation**, and roughly
half of *that* is graph optimisation work being redone on every single launch. That is where the next
second-scale win lives.

One further observation worth recording: the residual tokenizer cost is no longer dominated by the
larger file. `dict.SRC.json` (0.65 MB) now costs **twice** what `dict.TGT.json` (3.39 MB) costs, purely
because it is parsed first. Whatever is done next about the tokenizer should target cold-code
execution, not I/O — the I/O problem is solved.
