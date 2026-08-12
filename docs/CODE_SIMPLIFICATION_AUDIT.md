# BhashaBridge V4 Code Simplification & Runtime Audit

**Date:** 2026-08-12 · **Device for all figures:** Samsung SM-M315F (Exynos 9611, Armv8.0, 4+4)
· **Runtime:** ONNX Runtime 1.27.0 · **Direction:** EN→HI unless stated

**Scope rule applied throughout:** this audit ranks **runtime, algorithmic, memory and startup**
complexity above source-code complexity. A change that removes 200 lines and zero instructions is
recorded as P4 and recommended against, because the deadline is two days and the baseline is the
submission.

Every number below is either measured today or cited to its section in
`docs/OPTIMIZATION_SUMMARY.md`. Nothing is estimated as a percentage without saying so.

---

## 1. Executive Summary

**Inspected:** 11 production Kotlin files (`OnnxModels` 733, `Tokenizer` 487, `TranslateViewModel` 446,
`BhashaBridgeApp` 333, `MtEngine` 263, `Metrics` 161, `Decoder` 154, `CpuCapabilities` 143,
`ExecutionPolicy` 134, `GreedyDecoder`, `BeamSearchDecoder` 98), the Gradle asset configuration, the
Android backup rules, ~24 instrumented tests, and the ONNX Runtime 1.27.0 AAR itself
(`javap` + bytecode disassembly of `OnnxTensor`).

**Candidates found: 12.** P0: **0** · P1: **1** · P2: **3** · P3: **4** · P4: **4**.

**The headline finding is a negative one, and it is the most useful thing in this report:** the
per-token inference path is already at its floor. The only Java-side work left in it is a single
122,672-float logits copy, and §3.22 priced that entire class of change at **1.0% of end-to-end
latency**. There is no meaningful inference win available in application code before submission.

**Top 10 opportunities, ranked:**

| # | Candidate | Priority | Expected | Risk | Do it? |
|---|---|---|---|---|---|
| 1 | Lazy target-vocabulary strings (C1) | **P1** | ~600 ms cold start, ~3.5 MB heap | LOW–MED | **YES** |
| 2 | Merged decoder graph (C2) | P2 | ~152 MB RAM | **VERY HIGH** | NO — export change |
| 3 | Q16 embedding dedup (C3) | P2 | 79.3 MB APK | HIGH | NO — needs quality harness |
| 4 | Source vocabulary as packed index (C4) | P2 | ≤250 ms cold start | MEDIUM | NO — after C1, if time |
| 5 | Drop `dotprod` or document it as diagnostic (C5) | P3 | 0 runtime | LOW | Doc only |
| 6 | `prefix` array rebuilt per token (C6) | P3 | sub-µs | LOW | NO |
| 7 | Per-token feed `HashMap` (C7) | P3 | low µs | LOW | NO |
| 8 | `BeamSearchDecoder` unused in production (C8) | P3 | 0 runtime | LOW | NO — held for Q6 |
| 9 | Zero-copy logits via reflection (C9) | P4 | ~1%, unsafe | **HIGH** | **NO — warned against** |
| 10 | `decoder_init` last-position slicing (C10) | P4 | **0 — already measured** | — | **NO — closed §3.31** |

**Estimated total upside actually attainable in 48 h: ~600 ms of cold start (≈22%), from one change
in one file.** Everything else is either already done, needs a model re-export, or is worth ~1%.

**Major risks:** the two largest remaining wins (C2, C3) are both **export-side** changes that would
invalidate the frozen benchmark baseline and cannot be quality-gated in two days — no translation
quality harness exists in this repo. Attempting either before submission is the single biggest threat
to the deliverable.

---

## 2. Current Runtime Architecture

```
BhashaBridgeApp (process-scoped owner; two locks + borrow counter)
  └─ translator(direction)            ← evict-before-build (§3.24b)
       └─ MtEngine
            ├─ Tokenizer.load          ← 1036 ms: packed .vocab cache, JSON fallback
            └─ OnnxModels              ← 1657 ms: 3 graphs on 3 threads
                 ├─ encoder_int8.opt.onnx      ┐
                 ├─ decoder_init_int8.opt.onnx ├─ optimized ONNX, NO_OPT load
                 └─ decoder_step_int8.opt.onnx ┘  all → one shared weights.bin
```

Ownership is single-owner by construction: `BhashaBridgeApp` creates and releases every `MtEngine`;
Activities and ViewModels **borrow** and never release (R4.3/R4.4). This is the structural fix for
v3.4.1's L2 leak and is deliberately not simplified anywhere in this report.

**Per-translation path** (`MtEngine.translate`, `TranslateViewModel` runs it on a dedicated dispatcher,
never the main thread):

```
text → Tokenizer.encode → LongArray
     → OnnxTensor(input_ids) + OnnxTensor(attention_mask)   [2 tensors per translation]
     → encoder.run                                          [1 session run]
     → CachedLogitsSource
     → GreedyDecoder.decode  ──loop──►  decoder_init (1st token) / decoder_step (rest)
     → Tokenizer.decode → String
```

---

## 3. Hot Path Analysis

Measured shape of one long-sentence translation: **~627 ms, 12 tokens ≈ 52 ms/token.**

| stage | frequency | cost | notes |
|---|---|---|---|
| `Tokenizer.encode` | per translation | **0.43 ms** | measured, `BenchmarkSuiteTest` stage mark |
| tensor creation (ids, mask) | per translation | µs | 2 tensors |
| `encoder.run` | per translation | **85 ms** | measured stage mark |
| `decoder_init.run` | **once** per translation | ~49 ms | §3.31 |
| `decoder_step.run` | per token | ~38 ms | `step_us / steps` |
| **logits copy** | **per token** | **545.8 µs** | §3.22 — the only significant Java-side per-token cost |
| argmax over 122,672 floats | per token | linear scan, no allocation | |
| `applyRepetitionPenalty` | per token | O(n²), n ≤ 12 typical, bounded 128 | deliberate, zero-allocation |
| `blockRepeatedNgrams` | per token | O(n) | |
| feed `HashMap` (74 entries) | per token | low µs | |
| `Tokenizer.decode` | per translation | ~12 array lookups + 12 regex | |

**Conclusion: ≥97% of a translation is inside ORT kernels.** Every remaining application-level
allocation on the token loop is measured or bounded, and their sum is on the order of 1%.

---

## 4. Simplification Candidates

### Candidate C1 — Lazy target-vocabulary strings

**File:** `app/src/main/java/com/bhashabridge/app/mt/Tokenizer.kt`
**Function:** `load` / `loadVocab` / `Tokenizer.tgtIdToPiece`
**Priority: P1 · Risk: LOW–MEDIUM · Confidence: MEDIUM-HIGH on direction, BENCHMARK REQUIRED on size**

**Current implementation.** `load` builds `tgtIdToPiece: Array<String?>` by materialising **122,672
`String` objects** from the packed `.vocab` cache, at every cold start. The stage costs **745 ms** of
the 1036 ms tokenizer total (measured, 2026-08-12, runs 2–4 of a cold-launch series).

**The consumer does not need them.** `tgtIdToPiece` is read in exactly one place — `Tokenizer.decode`
— and decode performs **one lookup per generated token, ~12 per translation**. The app therefore
constructs 122,672 strings to serve about 12.

**Proposed simplification.** Keep the packed cache image in memory as it already exists on disk —
`ByteArray` plus an id-indexed offset/length index — and construct a `String` only inside `decode`.
The `.vocab` format written in §3.49 is already exactly this layout, so the change is to *stop
expanding it*, not to invent a representation.

**Why it may help.** It deletes 122,672 UTF-8 decodes and 122,672 object allocations from the startup
path and replaces them with an `IntArray` fill. The remaining work is a 2.5 MB file read plus an index
build. It also removes the largest single contributor to the tokenizer stage.

**Expected impact.** `tokenizer:tgt_dict` 745 ms → **est. 60–150 ms**; cold start 2736 ms →
**est. ~2150 ms (≈22%)**. Java heap: ~6.8 MB of `String` objects → ~3.2 MB (2.5 MB packed +
~0.7 MB index), a **~3.5 MB reduction**. Magnitude is **BENCHMARK REQUIRED**; the direction is not in
doubt because the work being removed is unconditional and counted.

**Validation.** Three cold launches, `engine_init` + `tokenizer:*` stage marks from `BB.Bench`;
`VocabCacheTest` (including its boundary-truncation case); `BenchmarkSuiteTest` output must remain
`पानी ।` and the long sentence byte-identical; `HiEnEngineTest` for the mirror direction.

**Potential regression.** Decode allocates ~12 short-lived strings per translation instead of zero —
irrelevant against a 627 ms translation. The real hazard is **index correctness**: an off-by-one in the
offset table silently returns the wrong piece. §3.54 is the standing warning that a parity check on two
sentences cannot detect a partial or shifted vocabulary; the test must assert **entry count and a
full-table round-trip against the map**, which `TokenizerTest` already does for the array form.

**Should attempt before submission: YES.** One file, reversible, and the only change in this report
with a measurable multi-hundred-millisecond effect.

> **IMPLEMENTED 2026-08-12 — §3.56.** Measured: `tokenizer:tgt_dict` **745 → 186 ms**, tokenizer total
> **1036 → 474 ms (−54%)**, `engine_init` **2736 → 2304 ms (−16%)**. Inference untouched (73.0 vs
> 72.8 tok/s, output identical). The estimate above said 60–150 ms for the stage and ~2150 ms for cold
> start; the stage landed at 186 ms and cold start at 2304 ms — the right size, slightly conservative
> of the prediction. Memory: **not separable from PSS noise**, no claim made.

---

### Candidate C2 — Merge `decoder_init` and `decoder_step` into one graph

**File:** `model_pipeline/` (export), consumed by `OnnxModels`
**Priority: P2 · Risk: VERY HIGH · Confidence: HIGH that the waste exists, UNKNOWN on the fix's cost**

**Current implementation.** Two decoder graphs. §3.55 measured that they share **884 KB of
323,756 KB** when loaded together — i.e. nothing — so `decoder_step`'s ~152 MB, which §3.30 proved is
byte-identical to data already inside `decoder_init`, is **resident twice**: about 37% of the process's
anonymous memory.

**Proposed simplification.** Export one merged decoder (`optimum`'s `decoder_model_merged` shape, an
`If` node selecting first-step from the rest) so one session holds one copy.

**Why it may help.** It removes the duplicate rather than trying to share it. §3.55 established that
sharing is **not reachable from Java**: `addExternalInitializers` — the only candidate API — cost
**+64.7 MB** where it should have saved 61.3, because ORT copies the supplied tensor into the session
instead of referencing it. Tensor creation from a mapped blob region is genuinely zero-copy (0 KB), so
the buffer half works; the session half does not.

**Expected impact.** Target: 322,872 KB → ~168,324 KB for the decoder pair. **UNKNOWN effect on
latency** — an `If` node can inhibit graph fusions.

**Validation.** `verify_cache.py` 7/7 → parity → `BenchmarkSuiteTest`. **Decode latency is the
acceptance test, not the memory**: §3.26 is the standing precedent that a memory-shaped change made
inference 4.6× slower.

**Should attempt before submission: NO.** Re-export invalidates the frozen baseline, and the failure
mode is a slower model discovered too late to revert cleanly.

---

### Candidate C3 — Collapse the duplicated tied embedding (Q16)

**File:** `model_pipeline/dedup_weights.py` + export
**Priority: P2 · Risk: HIGH · Confidence: HIGH that the duplication exists**

**Current implementation.** Confirmed by reading the initializer tables: `decoder.embed_tokens.
weight_quantized` UINT8 [122672, 512] and `onnx::MatMul_*_quantized` INT8 [512, 122672] are the same
matrix transposed under two quantization schemes — 62.8 MB each (EN→HI), 16.5 MB each (HI→EN). §3.30's
content addressing cannot collapse them because the bytes genuinely differ.

**Proposed simplification.** Export both uses from one scheme and orientation so `dedup_weights.py`
content-addresses them into one region; add a runtime `Transpose` on the gathered row (~12 lookups of
512 bytes per translation — free).

**Expected impact.** **79.3 MB** of APK (62.8 + 16.5). Note the queue previously recorded ~158 MB; that
counted both copies of a matrix still needed once (corrected in §3.53).

**Should attempt before submission: NO.** Re-quantizing an embedding may move the output, and the gate
is a **corpus quality check, which this repo does not have**. Shipping it unmeasured risks the one
thing the submission cannot afford: silently worse translations.

---

### Candidate C4 — Source vocabulary as a packed searchable index

**File:** `Tokenizer.kt` · **Priority: P2 · Risk: MEDIUM · Confidence: MEDIUM**

**Current.** `srcPieceToId` is a `HashMap<String, Int>` of ~32,322 entries built at startup:
**327 ms**, and it boxes every value as `java.lang.Integer`.

**Proposed.** Sorted packed pieces + binary search over the byte image, or an open-addressed
`String→int` map with a primitive value array, removing 32,322 `String` and 32,322 `Integer`
allocations.

**Why it may help.** Same mechanism as C1 — but `encode` looks up **by string** (three case variants
per word, plus greedy substrings), so the representation cannot be write-only; binary search over UTF-8
adds comparison cost on a path currently measured at 0.43 ms/translation.

**Expected impact.** Up to ~250 ms of startup. **BENCHMARK REQUIRED.**

**Should attempt before submission: NO** — take C1 first and only revisit if it lands early and clean.
Encode is correctness-critical and its lookup path is subtler than decode's.

---

### Candidate C5 — `dotprod` is detected and never used

**File:** `CpuCapabilities.kt`, `ExecutionPolicy.kt` · **Priority: P3 · Risk: LOW · Confidence: HIGH**

**Current.** Grep of every capability field's uses outside its own declaration and `describe()`:

| field | uses driving behaviour |
|---|---|
| `performanceCoreIds` | 7 — thread count + affinity string |
| `performanceCores` | 4 — the `[1,2]` clamp |
| `sme` | 5 — the `disableKleidiAi` predicate |
| **`dotprod`** | **0** |
| `i8mm`, `sve`, `sve2`, `sme2`, `fp16`, `neon` | declaration + `describe()` only |

**Assessment: these are diagnostic, and they should stay.** They appear in `BenchmarkSuiteTest`'s
device record and in every cross-device entry, which is what makes nine devices comparable and the
results reproducible. §3.52 established there is currently nothing for them to select: XNNPACK claims
**zero nodes** on these graphs (the hot GEMMs are `com.microsoft` contrib ops) and NNAPI is 2.25×
slower. Detection costs **one `/proc/cpuinfo` read and one cpufreq scan per process**, behind
`by lazy`.

**Action: documentation only** — state in `ExecutionPolicy`'s KDoc that these are diagnostic and
reproducibility fields, not dead code. **Do not delete.**

---

### Candidate C6 — `prefix` LongArray rebuilt every token

**File:** `GreedyDecoder.kt` · **Priority: P3 · Risk: LOW · Confidence: HIGH**

`val prefix = generated.toLongArray()` allocates a fresh array per token — O(n) copy per token, O(n²)
per translation, with n ≤ 12 typical and 128 bounded. **Sub-microsecond against a 52 ms token.**
`prefix` is genuinely needed: `CachedLogitsSource` compares it against `prevPrefix` to decide
init-vs-step, and `applyRepetitionPenalty`/`blockRepeatedNgrams` scan it.

**Should attempt: NO.** Removing it means threading mutable state through the `Decoder` interface for
no measurable gain — the abstraction is frozen (§3.2) and beam search shares it.

---

### Candidate C7 — Per-token feed `HashMap`

**File:** `MtEngine.kt`, `CachedLogitsSource.runStep` · **Priority: P3 · Risk: LOW**

A `HashMap<String, OnnxTensor>` of 74 entries is built per token (2 inputs + 72 cache tensors), already
pre-sized. A reusable `LinkedHashMap` cleared per step would remove one allocation and 74 rehashes per
token — **low microseconds against 52 ms**. The map is handed to ORT, which reads it during `run`;
reusing it is safe only while no reference escapes.

**Should attempt: NO.** Correct as written, and the win is unmeasurable.

---

### Candidate C8 — `BeamSearchDecoder` is not reachable in production

**File:** `BeamSearchDecoder.kt` (98 lines) · **Priority: P3 · Risk: LOW · Confidence: HIGH**

Referenced only by `DecoderTest` (JVM). `MtEngine` defaults to `GreedyDecoder`. It is **not dead code
to delete**: it is the implementation behind queue item Q6 and the reason the sliced `decoder_init`
graph is retained (§3.31). Zero runtime cost — it is never instantiated.

**Should attempt: NO.** Developer-experience only, and deleting it would discard a queued experiment.

---

### Candidate C9 — Zero-copy logits via ORT's private buffer

**File:** `MtEngine.kt`, `lastLogitsRow` · **Priority: P4 · Risk: HIGH · Confidence: HIGH (negative)**

**This is the one candidate this audit explicitly warns against.**

`lastLogitsRow` already carries the §3.22 optimisation: when `dec_len == 1` — every `decoder_step`,
i.e. all but the first token — it returns `buffer.array()` with no second copy. What remains is the one
copy inside `getFloatBuffer()`.

**Verified by disassembling ORT 1.27.0's `OnnxTensor`:** both `getFloatBuffer()` and `getByteBuffer()`
execute `allocate(capacity)` then `put(nativeView)` — a full heap copy. The only true native view is a
**private `getBuffer()`**, reachable solely by reflection.

**Why not:** it buys the same ~545.8 µs/token that §3.22 priced at **1.0% end-to-end**; it requires
reflection into a private method of a third-party native library **on the per-token path**; the
returned buffer would point into ORT-owned memory that `applyRepetitionPenalty` and
`blockRepeatedNgrams` **mutate in place**; and its validity is bounded by the result's lifetime, which
this code deliberately manages (`pastCache` close ordering). A mistake is a native crash or silent
corruption, two days before submission, for 1%.

**Should attempt before submission: NO.**

---

### Candidate C10 — `decoder_init` last-position slicing

**Priority: P4 · Status: ALREADY CLOSED — do not re-attempt**

Listed in the brief as "the known remaining opportunity". It was exported, gated 7/7, and measured:
**49.6 ms sliced against a 49.0 ms control — NO GAIN (§3.31).** The premise was wrong, not the
implementation: greedy seeds a one-token prefix, so `decoder_init` only ever runs at `dec_len = 1` and
there is no discarded row to remove. The sliced graph is retained for beam search (Q6), which is the
only caller that hands it a long prefix.

---

### Candidates C11–C12 — P4, no runtime effect

- **C11 `OnnxModels` is 733 lines.** The length is comments carrying measured history (why the blob is
  permanent, why `CACHE_FORMAT` leads the stamp, why `onnx` stays uncompressed). Removing them removes
  the reasons the traps were fixed. **Documentation is load-bearing here. DO NOT SIMPLIFY.**
- **C12 Two `OrtTuning` benchmark-only knobs** (`disableKleidiAi`, `disablePrepacking`,
  `gemmFastMathBf16`, `backend`, `graphDir`) are inert in production (`when` on a default enum, three
  boolean checks per session build — **three times per engine, not per token**). They are the
  instruments that produced §3.38–§3.52. **Keep.**

---

## 5. KV Cache Audit

Traced end to end: `token → decoder_init → logits + present.* → pastCache → decoder_step → past.* →
present.* → next token`.

| question | finding |
|---|---|
| where are the 72 tensors allocated? | **By ORT, inside `run()`** — as outputs in `OrtSession.Result`. The app never allocates them. |
| are Java wrappers created per token? | Yes — `past[i + 1] as OnnxTensor`, 72 casts of existing objects. No new native memory. |
| are cache buffers recreated per token? | **No.** The previous `Result` is closed *after* the next run produces its replacement; the app never copies cache contents. |
| are ORT input containers recreated? | Yes — one `HashMap` per token (C7), low µs. |
| do copies happen? | **No cache copy exists in application code.** The only per-token copy is logits (§3.22). |
| conversions / transposes? | None. `pastInputNames` is read from the graph in order, so `present[i+1] → past[i]` is positional with no reordering. |
| lifetime correctness | `pastCache?.close()` runs after the new result is obtained, so the tensors feeding `run` are alive for its duration. `close()` nulls the field. Correct. |
| JNI crossings per token | 1 `run` + 1 tensor create + 1 logits materialisation. **Not reducible without C9.** |

**In-place cache reuse was considered and is rejected:** ORT owns the output buffers and reuses its own
allocations across runs internally; writing into them from Kotlin is not a documented-safe operation.
The brief's own instruction — *do not propose in-place modification unless graph semantics make it
safe* — applies, and they do not.

**Verdict: the KV-cache path is already minimal. DO NOT SIMPLIFY.**

---

## 6. Logits Audit

| question | finding |
|---|---|
| is the whole vocabulary materialised? | One row of 122,672 floats per token — **not** the whole `[1, dec_len, vocab]` output (that was §3.22's fix). |
| is the whole prefix output materialised? | No — `dec_len == 1` on every `decoder_step`. |
| can only the final position be returned? | The graph already effectively does; slicing it is **§3.31, NO GAIN**. |
| can argmax avoid materialisation? | Only via C9's reflection — **recommended against**. |
| is data copied? | Once, inside `getFloatBuffer()` (`allocate` + `put`, verified by disassembly). The second copy is already gone. |
| boxed numerics? | **None** on this path — `FloatArray`/`LongArray` throughout. |
| JNI conversion? | One native→heap copy per token, 545.8 µs, **1.0% end-to-end** (§3.22). |

**Verdict: already optimised, with the remaining 1% locked behind an unsafe API. DO NOT SIMPLIFY.**

---

## 7. Tokenizer Audit

| question | finding |
|---|---|
| what is parsed? | Nothing, on the warm path. §3.49 replaced JSON parsing with a packed binary cache; JSON is a fallback only. |
| character-by-character parsing? | Only on cache miss (first launch per install). |
| how many strings are created? | **122,672 (target) + 32,322 (source) per cold start** ← C1 and C4. |
| are dictionaries duplicated? | No. §3.48 removed the map→invert duplication (516 ms). |
| can lookup be compacted? | Target: **yes, C1.** Source: possible, C4. |
| build-time parsing? | Partially — the cache is built on first launch, not at build time. Moving it into the APK is a further option, but the one-time cost is already amortised per install. |
| memory mapping? | The cache is read into one `ByteArray`; mmap would not help at 2.5 MB. |
| primitive arrays? | Target already is `Array<String?>`; C1 takes it to `ByteArray` + `IntArray`. |
| repeated parsing? | **No** — `by lazy` per engine, one engine per direction, evict-before-build. |
| resident unnecessarily? | The target vocabulary stays resident for the engine's life and is needed by decode. C1 shrinks it ~3.5 MB. |

**The brief's "~2.9 s tokenizer parsing" is stale.** It is **1036 ms** (§3.48 + §3.49). Note the ledger
figures were themselves corrected today: §3.49 originally published 514 ms, measured against a cache
that a test had truncated on an entry boundary — §3.54 restates them.

---

## 8. ORT / Session Audit

| area | finding |
|---|---|
| SessionOptions | Built fresh per session (3 per engine). `OrtTuning.toOptions()` applies only non-null knobs. Correct — ORT reads options at `createSession`. |
| graph optimization | `ALL_OPT` once per install at bake; `NO_OPT` on every later load (§3.47). Optimal. |
| arena | `cpuArena = false` — measured −37% process memory at no latency cost (Phase 7). |
| threading | `intra = perfCores/2` clamped `[1,2]`, big-cluster affinity. Clamp measured on three topologies (§3.37, §3.38, Pi 5 entry). |
| execution providers | CPU only. §3.52: XNNPACK claims **0 nodes**, NNAPI **+125%**. Nothing to select. |
| prepacking | On. §3.26: off is 4.6× slower. §3.45: baking prepacked weights is refused by the ORT format and costs +324 MB on the format that accepts it. |
| ORT-format loading | Replaced by optimized ONNX + shared blob (§3.47): −193 MB disk, −324 MB PSS, latency tied. |
| session reuse | One set per direction, process-scoped, evict-before-build. |
| parallel initialization | **Measured, not assumed**: serial is **+69%** worse on the warm path (§3.44). Note this does *not* contradict §3.29, where a *fourth* CPU-bound thread (the tokenizer) made cold start 6.6% worse — three loads are the same work split up, not a new competitor. |
| repeated init work | None found. `ExecutionPolicy.current` and `.capabilities` are both `by lazy`. |

**No ORT-side candidate remains that is both reachable from Java and beneficial.**

---

## 9. Memory Audit

| object | owner | created | destroyed | survives |
|---|---|---|---|---|
| `OrtEnvironment` | process singleton | first use | never (ORT contract) | everything |
| `MtEngine` | `BhashaBridgeApp` | `translator()` | `evictOtherDirections` / `onTrimMemory` | translations; **not** direction switch |
| 3 `OrtSession` | `OnnxModels` | engine construction | `release()` | with the engine |
| `weights.bin` (276 MB) | filesDir | first launch | never (permanent since §3.47) | reinstall |
| `Tokenizer` maps | `MtEngine` | construction | GC with engine | with the engine |
| `OrtSession.Result` (KV cache) | `CachedLogitsSource` | per token | next token / `close()` | one token |
| logits `FloatArray` | `lastLogitsRow` | per token | GC | one token |

**Measured facts:** the process holds ~424–460 MB PSS with ~410 MB anonymous; `weights.bin` maps
138.7 MB of address space at **RSS 0** (§3.50) — ORT reads initializers out and never touches the
mapping again. Under 1.79 GB of pressure: **zero major faults, zero swap-ins**, RssAnon −5%, latency
+10% and fully reversible; the LMK kills the process rather than swapping it (§3.50).

**100 direction switches** (§3.51): PSS drift **−8 MB**, native heap drift **−30 KB**, mapped bytes
constant, 0 crashes. The lifecycle is proven under repetition.

**Duplicate ownership found: none.** The only duplicated *memory* is the decoder weight pair (C2),
which is an export property, not an ownership bug.

**Do not simplify:** the two-lock split (`engineLock` / `loadLocks`) and the borrow counter exist
because a single monitor blocked the main thread for a ~10 s engine build, and because a speech session
pinned on the other direction must not have its sessions closed underneath it. These protect three
fixed native bugs.

---

## 10. Arm / CPU Policy Audit

1. **Which capabilities affect runtime?** `performanceCores` (thread count), `performanceCoreIds`
   (affinity string), `sme` (KleidiAI predicate). Three.
2. **Which are detected but unused?** `dotprod` (0 uses), and `i8mm`/`sve`/`sve2`/`sme2`/`fp16`/`neon`
   appear only in `describe()`.
3. **Duplicated information?** No — one detection, cached `by lazy`, reused.
4. **Repeated checks?** No. Detection is one `/proc/cpuinfo` read plus a cpufreq scan, once per process.
5. **Can the policy be simplified?** Not usefully. It is ~40 lines of rules with ~90 lines of measured
   justification.
6. **Does the policy actually control ORT?** **Partially, and honestly documented.** It controls thread
   count, affinity, arena and the KleidiAI predicate. It does **not** control kernel selection — §3.52
   proved that is not reachable: XNNPACK cannot claim `com.microsoft` contrib ops on any CPU.
7. **Device-specific logic hidden anywhere?** **No.** Every rule is capability- or topology-derived.
   `disableKleidiAi = caps.sme` is a capability predicate, not a device check — deliberately so, per
   §3.39, so non-SME parts stay byte-identical by construction.
8. **Unnecessary syscalls?** None on any hot path.

**Verdict: keep the detector whole.** The unused flags are diagnostic and reproducibility data feeding
`BenchmarkSuiteTest` and the nine-device database. Recommend a KDoc line saying so (C5).

---

## 11. Kotlin / Android Audit

| check | finding |
|---|---|
| UI-thread work | **None.** `TranslateViewModel.translate` runs `withContext(mt)` on a dedicated dispatcher; engine construction never touches main. |
| coroutine layers | One `viewModelScope.launch` + one `withContext` per translation. Minimal. |
| Flow transformations | `MutableStateFlow` + `MutableSharedFlow` exposed read-only. No chained operators on a hot path. |
| job management | The outstanding translation job is cancelled before a new one starts, and before a direction swap. Correct and necessary. |
| boxing | None found on the inference path (`LongArray`/`FloatArray` throughout). The tokenizer's `HashMap<String, Int>` boxes values — C4. |
| repeated context lookups | None on hot paths. |
| repeated resource loading | None — engines and Vosk models are process-scoped with stamps. |
| `Metrics` overhead | **Zero in release.** Every entry point is `inline` and guarded by `if (BuildConfig.DEBUG)`, so the calls and their arguments compile out. |

**No candidate above P3 on the Android side.**

---

## 12. JNI / Native Audit

| crossing | frequency | data | copied? |
|---|---|---|---|
| `createSession` | 3 per engine | file path | ORT reads the file |
| `OnnxTensor.createTensor` (ids, mask) | 2 per translation | small `LongArray` | yes, negligible |
| `OnnxTensor.createTensor` (decoder ids) | **1 per token** | 1 long | yes, negligible |
| `session.run` | **1 per token** | 74 tensor handles | handles only, no data copy |
| `getFloatBuffer` (logits) | **1 per token** | 122,672 floats | **yes — 545.8 µs, the only significant crossing** |
| `OnnxTensor.close` / `Result.close` | per token | — | — |
| `Debug.getNativeHeapAllocatedSize` | benchmarks only | — | not in production |

**Per-token crossings: 4.** Three are handle-only or single-value. The fourth is C9, recommended
against. **No JNI reduction is available without unsafe reflection.**

---

## 13. Already-Optimal / Do-Not-Touch Areas

| area | what it protects | why |
|---|---|---|
| `BhashaBridgeApp` two locks + borrow counter | **lifecycle safety** | Fixes v3.4.1 L2 leak, a ~10 s main-thread block, a Vosk use-after-free, and a lost `stop()`. §3.51 proves it holds over 100 switches. |
| `CachedLogitsSource` close ordering | **memory safety** | The previous cache is closed only after the next result exists; reordering is a use-after-free. |
| `lastLogitsRow` two-branch shape | **performance** | The `dec_len == 1` fast path is §3.22's measured win. The "obvious" cleanup (always slice) reinstates a full copy. |
| `pastInputNames` read from the graph | **KV-cache correctness** | Hard-coding the 72 names would drift from the export. |
| `intra=2` clamp + affinity | **Arm behaviour** | Measured on three topologies; `intra=4` is +36.7%/+93% on a 4-equal-core part. |
| `disableKleidiAi = caps.sme` | **Arm behaviour** | Keying on the capability keeps non-SME parts byte-identical by construction (§3.39). |
| Prepacking left on | **performance** | Off is 4.6× slower (§3.26). |
| `weights.bin` permanence + `purgeLegacy` | **correctness** | Deleting the blob breaks every launch after the first; §3.47/§3.54. |
| `CACHE_FORMAT` / `VOCAB_VERSION` stamps | **correctness** | A stale artifact that merely *looks* current is the failure both were added to prevent. |
| `noCompress` keeping `onnx` stored | **correctness** | `openFd` works only on stored entries and the cache stamp uses it. |
| `Metrics` inline+DEBUG gating | **performance** | Already zero-cost in release. |
| Long KDoc comments in `OnnxModels`/`Tokenizer` | **reproducibility** | They carry the measured reasons; deleting them re-opens closed traps. |

---

## 14. Recommended 48-Hour Plan

Ranked by *judging value × performance benefit × confidence ÷ risk ÷ time*:

| # | Action | Time | Risk | Why this order |
|---|---|---|---|---|
| 1 | **C1 — lazy target-vocabulary strings** | ~2 h incl. benchmarking | LOW–MED | Only change with a multi-hundred-ms effect; one file; reversible; validated by tests that already exist. |
| 2 | **Re-run the frozen benchmark set and freeze it** | ~1 h | NONE | The submission's evidence. `BenchmarkSuiteTest` + 3 cold launches after C1, or after nothing if C1 is skipped. |
| 3 | **C5 — document the diagnostic capabilities** | 15 min | NONE | Pre-empts a judge reading `dotprod` as dead code. Documentation only. |
| 4 | **Push the 62 unpushed commits** | 10 min | NONE | Today's work exists only on this laptop. |
| 5 | H4 release build / R8 | unknown | HIGH | Blocked on the owner's keystore; also the only route to AOT (§3.29). Do **not** start it late. |
| — | C2, C3, C4, C9 | — | — | **Explicitly deferred past submission.** |

**Stop rule:** if C1 is not passing parity by the halfway point, revert it and ship the current
baseline. The baseline is already a strong result.

---

## 15. Top 5 Changes Worth Actually Implementing

Only one change in this codebase currently clears the evidence bar. Listing five would be padding.

1. **C1 — lazy target-vocabulary strings.** ~600 ms of a 2736 ms cold start, ~3.5 MB of heap, one file,
   existing tests validate it. **BENCHMARK REQUIRED** for the exact figure.
2. **C5 — a KDoc line marking the diagnostic capability flags.** Zero runtime, prevents a
   misreading. Documentation only.

**Numbers 3–5 do not exist.** The honest finding of this audit is that BhashaBridge V4's application
code has already been optimised to the point where the remaining wins are in the **model export**
(C2: 152 MB, C3: 79 MB) and in the **release build** (H4), none of which can be safely validated in
two days.

**What the submission should claim is not that more was possible, but that the limits were measured:**
the per-token path is 97%+ ORT kernel time; kernel selection is unreachable because the graphs use
contrib ops (§3.52); runtime weight sharing is unreachable because ORT copies supplied initializers
(§3.55); and the remaining startup cost is one vocabulary expansion (C1) plus ~1.6 s of session load
already priced at ~790 ms prepacking and ~690 ms graph residual (§3.44, §3.46).
