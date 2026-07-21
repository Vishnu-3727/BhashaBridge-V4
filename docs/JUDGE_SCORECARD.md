# Judge Scorecard — self-assessment

An honest scoring of BhashaBridge V4 against the Arm AI Optimization Challenge criteria, written by
the team that built it. Every score carries its evidence and its weaknesses. Where the project is
thin, this says so — a scorecard that only argues for itself is worth nothing to a judge.

**Total: 74 / 100.**

| Category | Weight | Score |
|---|---|---|
| Technological Implementation | 40 | **31** |
| User Experience / Developer Experience | 15 | **11** |
| Potential Impact | 20 | **14** |
| WOW Factor | 25 | **18** |
| **Total** | **100** | **74** |

---

## Technological Implementation — 31 / 40

### Justification

The core work is a genuine optimisation problem solved from first principles, not a library swap.
IndicTrans2's shipped ONNX decoder had **no KV-cache ports at all** — the v3.4.1 export wrapper
exposed only `input_ids` / `encoder_hidden_states` / `encoder_attention_mask`, so the graph
physically could not cache, and every token re-ran the whole prefix. Optimum has no config for the
custom `IndicTrans` architecture, so the cached decoder was **hand-exported** as `decoder_init` +
`decoder_step` graphs with 72 named cache tensors (18 layers × 4), then quantized, then wired into a
runtime behind an abstraction that did not change.

The measured result is the headline: decode complexity moved from O(n²) to O(n), which shows up as
tokens/sec *rising* with output length instead of falling.

### Evidence

| Claim | Measurement | Source |
|---|---|---|
| KV cache works and helps | **2.12× at 12 tokens**, 1.48× @6, 1.06× @2 | `CACHE_BENCHMARK.md` |
| Complexity actually changed | tokens/s 14.9 → 21.6 cached; 13.9 → 9.5 uncached | same |
| Exported graphs are correct | 7/7 numeric gate, cached vs uncached max Δ 9.06e-06, identical greedy tokens | `verify_cache.py` |
| INT8 preserves behaviour | identical token sequences, max logit Δ 0.448 | `EXPORT_WITH_CACHE.md` |
| Size reduction | 1869 MB → 472 MB (3.96×) | same |
| Runtime tuning is evidence-based | one variable at a time, 12 configs, 30 runs each | `ORT_TUNING.md` |
| Results are reproducible | Phase 10 medians within **0.16%** of Phase 8 on all three sentences | `VALIDATION_REPORT.md` §2.2 |
| Correctness is protected | 24 automated tests, 100% pass; parity asserted against a golden output on device | §4 |
| The architecture holds | backend diff empty across the entire UI rebuild; `MtEngineInstrumentedTest` still green | `UI_RECONSTRUCTION.md` |

The engineering discipline is itself evidence: `LogitsSource` was designed in Phase 4 as the seam the
cache would later need, and when the cache landed in Phase 6B **no decoder code changed**. Native
resources are owned once at process scope with a single release trigger, which is verified live —
rotation and locale change reload nothing, and `onTrimMemory` drops 630 MB → 454 MB.

### Weaknesses

- **Half the product is missing.** HI→EN cached graphs were never exported. The app is bidirectional
  in design and one-directional in fact. This is the single biggest deduction.
- **No execution-provider selection.** The capability detector surfaces dotprod/i8mm/SVE2/SME2, but
  nothing acts on those flags yet; INT8 acceleration comes from MLAS's own dispatch, which the project
  did not have to build.
- **One device, one microarchitecture.** Everything is measured on Armv8.0 NEON-only silicon. The
  portability argument is by construction, not by measurement.
- **Known ceiling left in place**: the per-step logits read still boxes through `OnnxTensor.value`
  instead of `getFloatBuffer`, documented and deliberately not chased.
- R8 is disabled in release builds.

---

## User Experience / Developer Experience — 11 / 15

### Justification

The app is a finished product, not a benchmark harness with buttons. It works fully offline with **no
network permission at all**, has a coherent visual identity, a first-run tour, bilingual UI driven by
per-app locales, live speech with a waveform, emergency phrases that bypass the model entirely, and a
"Model & device" panel that shows the user the actual detected CPU and the runtime policy derived
from it.

The developer experience is unusually strong for a hackathon entry: the model pipeline is
reproducible from a script, benchmarks are re-runnable tests rather than pasted screenshots, raw
JSONL evidence is committed, and the architecture rules are written down and enforced — including a
document about what the previous version got wrong and why.

### Evidence

| Claim | Evidence |
|---|---|
| Offline by construction | No `INTERNET` permission in the manifest; only `RECORD_AUDIO` |
| Interactive quickly | First frame in 1.2–1.9 s |
| Correct under lifecycle stress | 58/59 functional checks pass; rotation, backgrounding, locale change, trim-release and process restart all verified |
| Privacy in release builds | **Zero** app log lines in a full release session — user speech and translations cannot reach a log |
| Reproducible pipeline | `cached_export.py` → `quantize_cached.py` → `verify_cache.py` (7-check gate + model-free self-check) |
| Honest documentation | 15 documents including reverted experiments and a limitations list |
| Build from clean | `README.md` + `THIRD_PARTY_NOTICES.md` |

### Weaknesses

- **27 s from launch to first translation.** Hidden behind a progress screen with animated dots, but
  it is the first thing a judge will feel, and it repeats after a memory trim.
- **561 MB APK.** Not installable from Play without asset delivery; a judge must side-load.
- **No landscape layout** — the orientation is now locked to portrait because the landscape layout was
  found unusable during this phase.
- No demo video yet, and the screenshots are static.
- Model binaries are not in the repo (correctly), so a fresh clone cannot build a runnable APK without
  running the export pipeline first.

---

## Potential Impact — 14 / 20

### Justification

The target is real and specific: English↔Hindi communication with **no connectivity**, on the kind of
phone people in that situation actually carry. The validation device is a 2020 budget Samsung with an
Exynos 9611 — not a flagship — and it runs a 200M-parameter transformer at 667 ms for a full sentence
while staying 0.5 °C above idle. Emergency phrases deliberately bypass the model so that the
safety-critical path cannot fail because a model is loading or a translation is uncertain.

Offline capability is the impact argument: disaster response, rural clinics, travel without roaming,
and any situation where sending speech to a server is impossible or unacceptable. Nothing leaves the
device, and that is enforced by the permission set rather than promised in a privacy policy.

### Evidence

| Claim | Evidence |
|---|---|
| Runs on low-end hardware | Exynos 9611, Armv8.0, 4×A73+4×A53 — full sentence in 667 ms |
| Sustainable under use | 90 consecutive translations: memory flat, +0.5 °C peak, no throttling signature |
| Genuinely offline | No network permission; models and recogniser bundled |
| Safety path is failure-proof | 32 human-translated emergency pairs, no model on the path, verified to emit no engine call |
| Speech works end to end | 2.64 s of audio → exact transcript → correct Hindi, 2.5 s total |

### Weaknesses

- **One language pair, one direction.** A Hindi speaker cannot currently be understood by an English
  speaker with this build — which is half the use case it argues for.
- **No field validation.** No user testing with the populations described; the impact case is
  reasoned, not evidenced.
- **Recognition quality unmeasured** against real voices; the bundled Vosk Hindi model's published WER
  (15–39% by test set) is a real ceiling on the experience.
- **Distribution is unsolved** at 561 MB, which limits reach more than any technical factor.
- IndicTrans2 supports 22 languages; this app exposes one pair, so the ceiling is architectural
  convenience rather than ambition.

---

## WOW Factor — 18 / 25

### Justification

The "wow" here is not a demo trick — it is that a decoder which *could not cache* now caches, on a
budget phone, with the receipts to prove it. A judge can re-run every number. The project publishes
its failures: four optimisations were measured, found harmful, and reverted with their numbers intact
(intra-op 8 at +90%, graph optimisation off at +13%, parallel inter-op at +10%, and the intuitive
"use all four big cores" thread rule that regressed by 8% with 5× the jitter). Very few submissions
show the experiments that did not work.

The capability-aware runtime is the second surprise: the app reads `/proc/cpuinfo` and `cpufreq`,
derives its own thread count, and **displays that reasoning to the user** in the Model & device panel.
The same binary configures itself differently on different Arm cores, with no device list anywhere in
the code.

And it refuses to overclaim: SME2 is explicitly *not* claimed, only detected, because no Armv9 device
was available to validate it.

### Evidence

| Claim | Evidence |
|---|---|
| A cache where none existed | Hand-exported `decoder_init` / `decoder_step`, 72 cache tensors, verified numerically |
| Measured, not asserted | Every claim traces to a JSONL file and a re-runnable test |
| Negative results published | `ORT_TUNING.md`, `ARM_PLATFORM_OPTIMIZATION.md`, `OPTIMIZATION_SUMMARY.md` |
| Self-configuring runtime | `CpuCapabilities` + `ExecutionPolicy`, surfaced in the UI |
| Reproducibility | Phase 10 re-run matched Phase 8 to 0.16% |
| Restraint | SME2 documented as "ready", never claimed |

### Weaknesses

- **No Armv9 demonstration.** The most exciting claim — that the same binary gets faster on SME2/i8mm
  silicon — is architectural, not shown. On the available device it is NEON only.
- **The demo is quiet.** A 667 ms translation on a budget phone is impressive to an engineer reading
  the numbers, less so to someone watching a screen; there is no side-by-side video against the
  uncached build, and no live "watch it get faster" moment.
- **Only one modality of novelty.** The UI is a competent rebuild of the previous version, not a new
  interaction idea.
- The missing HI→EN direction undercuts the "bridge" framing at exactly the moment a judge tries the
  swap button.

---

## What would raise the score most

Ranked by points per unit of work, honestly:

1. **Export and quantize HI→EN** (+4–6 across Implementation and Impact). The pipeline exists; this is
   a known, bounded task and it removes the biggest hole in the story.
2. **Cut or hide the 27 s load** (+2–3 in UX). Lazy per-session loading, a smaller distilled model, or
   loading the encoder first so the UI can accept input sooner.
3. **Validate on Armv8.2+ silicon with dot-product** (+2–3 in WOW). One run on a newer phone turns the
   portability claim from designed to demonstrated.
4. **A 60-second side-by-side demo video**, uncached vs cached (+2 in WOW).
5. **Solve distribution** — Play Asset Delivery or first-run model download (+1–2 in Impact).
6. **Measure WER against real voices** (+1–2 in Impact) — it is the number that decides whether people
   would actually use it.
