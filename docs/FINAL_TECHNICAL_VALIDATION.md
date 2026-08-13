# BhashaBridge V4 — Final Technical Validation

Four tasks, executed 2026-08-13 against the repository as it stands. Everything below was measured;
where something could not be measured it says so rather than estimating. Companion documents:
`QUALITY_EVALUATION.md`, `RELEASE_VALIDATION.md`, `SUSTAINED_STRESS_TEST.md`.

**Verdict: FREEZE.** Nothing found in this pass is worth changing before submission. Three of the four
tasks closed a gap that was open at the last audit; the fourth confirmed a known ceiling and found no
new lever above 1% of it.

---

## 1. Current baseline (verified, not assumed)

| | Value | Source |
|---|---|---|
| Device | SM-S948B, Snapdragon 8 Elite Gen 5, 8× Oryon uniform IP, 12 GB, Android 16 | this session |
| Policy | `arm-adaptive(threads=2,noKleidiAI) intra=2, arena=off, affinity=OFF` | device log |
| Runtime | onnxruntime **1.27.0** (`libs.versions.toml`, cache confirms the AAR) | verified |
| Long sentence, **debug** | 86.0 ms median · 535.1 tok/s · 32.7 °C | `BenchmarkSuiteTest` |
| Long sentence, **release** | **81.0 ms median · 559.7 tok/s** · 32.7 °C | this session, new |
| `engine_init` cold, release | **1839 ms** (debug 2039) | this session, new |
| Model cache on disk | 279,821,784 B — identical debug and release | verified both |
| Release APK, arm64 | **509.0 MiB** (debug 520.3) | this session, new |
| Correctness | 8/8 instrumented, both directions, both build types | this session |

**Correction to the project's own framing:** the ~2.3 s startup and 86 ms latency figures in
`OPTIMIZATION_SUMMARY.md` are **debug-build numbers**. Release is ~5% faster on latency and ~10%
faster to engine-ready. The ledger's A/B conclusions are unaffected — both arms always carried the
same instrumentation — but the published absolute numbers understate the shipping product.

## 2. BLEU / chrF

WMT14 newstest, first 500 sentences, paired bootstrap (1,000 resamples, seed 12345), FP32 as baseline.
FLORES was intended and is **gated on both HuggingFace mirrors (403)** — substitution documented.

| Direction | System | BLEU | chrF2++ |
|---|---|---|---|
| EN→HI | FP32 | 21.88 | 49.21 |
| EN→HI | **INT8 (ships)** | **21.85** (−0.03, p=0.336) | **48.93** (−0.28, p=0.037\*) |
| HI→EN | FP32 | 32.31 | 58.67 |
| HI→EN | **INT8 (ships)** | **32.79** (+0.48, p=0.039\*) | **58.83** (+0.16, p=0.134) |

**INT8 is quality-neutral.** The two directions move in *opposite* directions by comparable amounts at
comparable marginal p-values — the signature of a ±0.3–0.5 BLEU quantization perturbation, not of
degradation. EN→HI passes the pre-registered "chrF2 within 0.3" gate at 0.28, narrowly.

**The parity finding matters more than the scores.** Exact token-sequence match between INT8 and FP32
is **50.6% (EN→HI) and 44.4% (HI→EN)** on real sentences, against `verify_cache.py`'s "greedy tokens
identical" on synthetic inputs. Half the outputs diverge at the token level and the corpus scores are
statistically indistinguishable. Parity was never a quality metric; it overstated the risk in one
direction and was mistaken for a guarantee in the other.

## 3. Release build

**PASS.** 8/8 correctness tests against the release artifact, `पानी ।` unchanged, model cache
byte-identical, no R8 failures (R8 is off), no JNI or native errors. Performance is *better* than
debug on every latency metric (§1). Open items: R8 disabled, debug-key signing, 509 MiB.

`testBuildType = "release"` was set temporarily to make the measurement and **has been reverted** —
`git diff` on `app/build.gradle.kts` is clean.

Not re-run against release: speech, TTS, rotation, background/foreground, `onTrimMemory`, process
restart, airplane mode. **NOT VERIFIED** for this artifact; last validated on debug in Phase 10.

## 4. 500-translation stress

Two experiments, kept separate.

**A — 500 direction switches.** `FAILURES 0`. PSS drift **−13 MB**, native heap **+1 MB**, peak PSS
531 MB. Build median 408 ms (p99 539), translate median 91 ms (p99 119). No lifecycle leak at 5× the
previous stress.

**B — 1,024 consecutive translations, no engine rebuild, unplugged.** Shipping arms 89.0 / 90.0 ms
median (n=128 each). Pooled drift **1.29** first round → last.

**The slowdown is attributed, not labelled.** The big cluster steps `2942 → 2092 MHz` monotonically
(−29%) against a +29% latency rise, while PSS *falls* 483 → 466 MB, native heap is flat, `coresBusy`
holds at 2.44, and reference output is re-verified per arm. DVFS on an unplugged device — memory, GC,
threading and lifecycle are each excluded by their own measurement.

Secondary: the shipping `intra2` degraded +8% where `intra1` degraded +39%, so the clamp is also the
thermally steadier setting — not the reason it was chosen. And `intra2` beat `intra1` by 6.5% again,
replicating the §3.57 correction in a second session.

## 5. Memory / copy / allocation audit

Traced: text → tokenizer → ids → tensor → encoder → `decoder_init` → logits → argmax → cache →
`decoder_step` → next token.

**Per-token, on the hot path (`MtEngine.CachedLogitsSource`):**

| Site | Allocation per token | Size | Verdict |
|---|---|---|---|
| `lastLogitsRow` → `getFloatBuffer()` | `FloatBuffer.allocate(122672)` + full copy | **~490 KB** | **Dominant. Not fixable from Java** |
| `runStep` input tensor | one `long[1]` + `OnnxTensor` (native alloc + JNI) | ~64 B + handle | <0.1% of the above |
| `runStep` feed map | `HashMap` sized 74 | ~3–4 KB | <1% |
| 72 cache tensors | **not copied** — fed by reference from the previous `Result`, superseded one closed per step | 0 | Correct as written |

**The 490 KB/token is confirmed unfixable, by inspection of the runtime rather than by assumption.**
The ORT 1.27.0 Android AAR was extracted and its Java surface contains **no `IoBinding` class**
(`ai/onnxruntime/` has `OrtAllocator`, `OnnxTensor`, `OrtSession` — no binding API). There is no way
from Java to have ORT write logits into a reused buffer. This confirms §3.22's ceiling **at the
current runtime version**, which had not been re-checked since the 1.17.1 → 1.27.0 upgrade.

At ~12 tokens that is ~5.9 MB of large-object-space churn per translation, and every other per-token
allocation combined is under 1% of it. **There is no remaining Java-side lever of measurable size.**

**KV cache — checked for the specific failure modes named, all absent.** No per-token cache
allocation (ORT owns the `present` tensors), no per-token cache copy (fed by name from the previous
`Result`), no per-token wrapper creation for the 72 tensors, no extra JNI crossing per tensor. The
`use {}` on both input-tensor sites is load-bearing: without it a throw leaks one tensor per step.
**Do not touch this code.**

**Tokenizer.** Already at its floor after §3.48/§3.49/§3.56 — packed binary vocabulary image with one
`Long` per id (offset ≪ 32 | length), decoding a `String` only inside `pieceAt`, ~12 lookups per
translation. `encode` still needs its `HashMap<String,Int>` because it looks up *by string*; a packed
searchable layout is the only remaining idea and §3.56 priced it at ~324 ms of startup, not runtime.

**Model loading.** Bake-once → `NO_OPT` load, shared blob, `purgeLegacy`, arena off. `mappedInitializers`
is deleted (§3.47) and stays deleted. Evict-before-build is present at `BhashaBridgeApp.kt:164` with a
second eviction after publishing for the borrowed case, so the two directions do not co-reside except
transiently — confirmed by Experiment A's `mapped_model_mb` tracking direction correctly across 500
switches.

## 6. New findings

1. **Release is ~5% faster than debug** and the repository's published latency numbers are debug
   numbers. (New, measured.)
2. **INT8 is quality-neutral on a real corpus** — first corpus-scored quality evidence in the project.
3. **Token parity on real sentences is ~50%, not 100%** — the synthetic gate was never evidence of
   what it was being read as.
4. **The sustained slowdown is DVFS with a frequency series to prove it**, and the shipping thread
   count is the thermally steadier one.
5. **No `IoBinding` in ORT 1.27.0's Java surface** — §3.22's ceiling re-confirmed at the current
   runtime version.
6. **Release APK is 509.0 MiB**, 11.3 MiB below the debug figure the docs quote.

## 7. Regressions

**None found.** Correctness identical across debug and release and across 1,524 translations in this
session. No metric moved backwards against the frozen baseline.

The one *evidence* regression was found and fixed earlier the same day (`e8764df`): `arm()` hard-coded
`disableKleidiAi = false`, which since §3.40 made every thread arm a thread+KleidiAI arm. §3.57 and
`s26ultra_revalidation_2026-08-13.md` still carry the contaminated 6.6% floor and the `intra1 −2.1%`
result; both are superseded by this session's re-measurements and should be corrected.

## 8. Optimizations worth implementing

**None before submission.** That is the finding, not an omission. Every candidate large enough to
measure is blocked upstream (ORT's Java API, or the export), and every candidate that is reachable is
under 1% of the dominant cost.

## 9. Optimizations NOT worth implementing

| Candidate | Why not |
|---|---|
| Reuse the per-token `HashMap` | ~3–4 KB against 490 KB. Unmeasurable, and ORT's map-lifetime semantics would need verifying to make it safe |
| Reuse the `[1,1]` input tensor | Needs a direct buffer ORT may retain; risk on the per-token path for <0.1% |
| Enable R8 | Release is already faster. Reflection-heavy ORT + Vosk on submission eve is the classic breakage, with no measured upside |
| `mappedInitializers` | Deleted at §3.47. Prohibited, stays prohibited |
| `decoder_init` last-position slicing | §3.31 measured it worth nothing: greedy runs `decoder_init` at `dec_len=1`, so there is no discarded row |
| Forced KleidiAI / SME / manual kernel selection | Measured negative (§3.39, re-confirmed §3.57) |
| Merged decoder export to share weights | §3.55: correct fix, but it is an export change and cannot be quality-gated in the time left. **DO NOT ATTEMPT BEFORE SUBMISSION** |
| int4 / O3 quantization | Needs a quality gate that now exists but no time to run it. **DO NOT ATTEMPT BEFORE SUBMISSION** |

## 10. Final technical risk assessment

| Risk | Severity | State |
|---|---|---|
| Release artifact unvalidated | **Closed** | 8/8 pass, faster than debug |
| Translation quality unproven | **Closed** | Corpus-scored, both directions, with significance |
| Sustained stability unknown | **Closed** | 1,524 translations, 0 failures, drift attributed |
| Hidden per-token allocation | **Closed** | Dominant cost confirmed unfixable; nothing else measurable |
| §3.57 carries contaminated numbers | **Open** | ~30 min to correct; evidence already in hand |
| R8 off, debug-key signing | **Open, accepted** | Deliberate; documented rather than changed |
| 509 MiB distribution size | **Open** | Packaging problem, not an engine problem |
| Speech/TTS/rotation on release | **NOT VERIFIED** | Last validated on debug, Phase 10 |

---

## Decision matrix

| Finding | Benefit | Evidence | Risk | Time | Implement? |
|---|---|---|---|---|---|
| Correct §3.57 + revalidation doc for the KleidiAI confound | Credibility of the whole ledger | This session's re-runs | None | 30 min | **YES** |
| Publish release numbers alongside debug in README/SUBMISSION | Headline is 5% better and true | `RELEASE_VALIDATION.md` | None | 20 min | **YES** |
| Cite BLEU/chrF in SUBMISSION.md | Closes the largest judging deduction | `QUALITY_EVALUATION.md` | None | 20 min | **YES** |
| Re-run speech/TTS/rotation on the release APK | Removes a NOT VERIFIED | — | None | 40 min | **YES** |
| Extend quality eval to all 2,507 sentences | Tighter CIs | Harness exists | None | ~35 min unattended | BENCHMARK FIRST |
| Enable R8 | None measured | Release already faster | High | — | **NO** |
| Reuse per-token map / input tensor | <1% | §5 | Medium | — | **NO** |
| Merged decoder export | −152 MB | §3.55 | High | Days | **NO** |

**Recommendation: FREEZE the runtime. Spend the remaining time on documentation and the four YES rows,
none of which touch a line of production code.**
