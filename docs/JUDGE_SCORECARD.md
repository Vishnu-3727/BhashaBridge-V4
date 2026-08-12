# Judge Scorecard — self-assessment

An honest scoring of BhashaBridge V4 against the Arm AI Optimization Challenge, written by the team
that built it. Every score carries its evidence **and** its weaknesses. Where the project is thin,
this says so — a scorecard that only argues for itself is worth nothing to a judge.

**Revised 2026-08-10.** The previous revision scored **74/100** and was written around Phase 10. It
was never updated through Phase 12, the nine-device campaign, the ORT upgrade, the startup work or
the 2026-08-06 audit, so it argued against the project using six deductions that had since been
closed. Those are listed in §0 rather than quietly deleted — a scorecard that revises itself upward
owes the reader the diff.

**Total: 81 / 100.**

| Category | Weight | Previous | Now |
|---|---|---|---|
| Technological Implementation | 40 | 31 | **34** |
| User Experience / Developer Experience | 15 | 11 | **12** |
| Potential Impact | 20 | 14 | **15** |
| WOW Factor | 25 | 18 | **20** |
| **Total** | **100** | **74** | **81** |

---

## 0. What changed since the 74/100, and what did not

Six deductions in the previous revision are no longer true:

| Was scored against the project | Now |
|---|---|
| "**Half the product is missing.** HI→EN cached graphs were never exported ... the single biggest deduction" | Phase 12 exported them from the named `ai4bharat/indictrans2-indic-en-dist-200M` checkpoint through the same 7-check verifier. Measured 23.8 / 43.2 / **76.7 ms** at 2/6/12 tokens, 177 tok/s (§3.19, `S26U_EXPERIMENTS.md` §4b). The R-PROV provenance gap is closed |
| "**27 s from launch to first translation** ... the first thing a judge will feel" | **~5,134 ms** cold-launch `engine_init` median on the same device (§3.10–§3.14, §3.29) |
| "**One device, one microarchitecture.** The portability argument is by construction, not by measurement" | Nine devices, four vendors, Armv8.0 → Armv9. Same APK, no recompile: **50.3 → 412.8 tokens/sec** (`CROSS_DEVICE_REPORT.md`) |
| "**No Armv9 demonstration** ... on the available device it is NEON only" | S26 Ultra: SVE2 + SME. Dispatch **proven** by `simpleperf` + capstone disassembly (`smopa za0.s`) and **priced** by A/B at +4.9–9.1% (§3.20) |
| "**Known ceiling left in place**: the per-step logits read still boxes through `OnnxTensor.value` ... deliberately not chased" | Fixed and priced: 1.75× on the reader at 122k vocab, 6.55 ms per 12-token translation, ~1.0% end-to-end (§3.22) |
| "SME2 is explicitly *not* claimed ... no Armv9 device was available" (scored as restraint) | Still restraint, but for a different reason: **SME2 is absent from the S26 Ultra too** (`sme` yes, `sme2` no). The claim was never made and now cannot be |

Three that were true then and are true now: **R8 disabled**, **no execution-provider selection**, and
**model binaries are not in the repo**, so a fresh clone cannot build a runnable APK without running
the export pipeline.

And five deductions the previous revision did not have, because the work that created them had not
happened yet. They are in the sections below: `mappedInitializers` shipping off with its justifying
claim unproven, one shipping default resting on INFERRED evidence, an asset payload that reached
909 MB before being cut to 619 MB, the release build being unshippable, and the audit's finding that
four correctness defects had shipped.

---

## 1. Technological Implementation — 34 / 40

### Justification

The core work is a genuine optimization problem solved from first principles, not a library swap.
IndicTrans2's shipped ONNX decoder had **no KV-cache ports at all** — the v3.4.1 export wrapper
exposed only `input_ids` / `encoder_hidden_states` / `encoder_attention_mask`, so the graph
physically could not cache and every token re-ran the whole prefix. Optimum has no config for the
custom `IndicTrans` architecture, so the cached decoder was **hand-exported** as `decoder_init` +
`decoder_step` with 72 named cache tensors (18 layers × 4), verified numerically, quantized, and
wired into a runtime behind an abstraction that did not change.

The measured result is the headline: decode complexity moved from O(n²) to O(n), which shows up as
tokens/sec *rising* with output length instead of falling.

### Evidence

| Claim | Measurement | Source |
|---|---|---|
| KV cache works and helps | **2.12× at 12 tokens**, 1.48× @6, 1.06× @2 | `CACHE_BENCHMARK.md` |
| Complexity actually changed | tokens/s 14.9 → 21.6 cached; 13.9 → 9.5 uncached | same |
| Exported graphs are correct | 7/7 numeric gate, max Δ 9.06e-06, identical greedy tokens | `verify_cache.py` |
| INT8 preserves behaviour | identical token sequences, max logit Δ 0.448 | `EXPORT_WITH_CACHE.md` |
| Size reduction | 1869 MB → 472 MB (3.96×) | same |
| **Both directions ship** | HI→EN re-exported from a named checkpoint, same verifier, 76.7 ms @12 tokens | §3.19, `S26U_EXPERIMENTS.md` §4b |
| **Startup is 5.3× faster** | 27.0 s → ~5.1 s, driven by instrumentation that found 49% of it was a JSON parser | §3.10–§3.14 |
| Runtime tuning is evidence-based | one variable at a time, 12 configs, 30 runs each | `ORT_TUNING.md` |
| Memory work is measured, not asserted | arena off −38%; evict-before-build swap peak −36.7%; `release()` returns 557.8 → 13.2 MB allocated | §3.8, §3.24b, §3.25 |
| Results are reproducible | Phase 10 medians within **0.16%** of Phase 8 on all three sentences | `VALIDATION_REPORT.md` §2.2 |
| Correctness is protected | 94 test methods; parity asserted against a golden output on device | `app/src/test`, `app/src/androidTest` |
| The architecture holds | backend diff empty across the entire UI rebuild | `UI_RECONSTRUCTION.md` |

The engineering discipline is itself evidence: `LogitsSource` was designed in Phase 4 as the seam the
cache would later need, and when the cache landed in Phase 6B **no decoder code changed**. Native
resources are owned once at process scope with a single release trigger — rotation and locale change
reload nothing.

### Weaknesses

- **The release build cannot ship.** R8 disabled, signed with the **SDK debug key**, `versionCode`
  never incremented — and that `versionCode` feeds `OnnxModels.cacheStamp`, so two different builds
  share an ORT cache key. `AUDIT_2026-08-06.md` H4. This is the largest remaining deduction and it is
  not a research problem.
- **No execution-provider or kernel selection.** The detector surfaces dotprod/i8mm/SVE2/SME, but
  nothing acts on those flags. INT8 acceleration comes from MLAS's own HWCAP dispatch, which this
  project did not have to build. The Arm-specific *code* contribution is thread policy and
  measurement; the ISA wins are inherited.
- **One shipping default rests on INFERRED evidence.** The intra-op clamp `[1,4]` → `[1,2]` comes
  from the S26 Ultra's sweep table, not from a run after the edit (§3.21). That device is no longer
  available and neither remaining device *reaches* the bound, so it can only be closed by an explicit
  thread sweep on a second topology.
- **`mappedInitializers` ships off with its justification unproven.** 451 MB does become file-backed
  and anonymous heap does fall 151 MB, but the OOM-survival benefit that motivated it was measured
  and **not established** (1/6 vs 2/6 kills — noise), and a measured *cost* appeared instead: ~2×
  latency while reclaimed pages are re-read (§3.27, §3.28). Correctly not shipped; still an
  optimization that did not land.
- **Four correctness defects shipped and were found by audit, not by tests** — a native
  use-after-free window, a lost-stop race, a thread-unsafe field, and silent truncation of long
  translations. All fixed, but they were in shipped code and the truncation one had *survived a
  commit that claimed to fix truncation*.
- **Model quality is not addressed.** No fine-tuning. See §5.
- **The asset payload is still 619 MB**, though it is no longer a regression: 909 MB was an export
  defect, not the cache's price — `decoder_step` shipped a second copy of weights already in
  `decoder_init`, and one shared blob took the APK to 617 MB with output bit-identical (§3.30). The
  remaining size is the model, and only a smaller model or a pruned vocabulary moves it.

---

## 2. User Experience / Developer Experience — 12 / 15

### Justification

The app is a finished product, not a benchmark harness with buttons. Fully offline with **no network
permission at all**, a coherent visual identity, a first-run tour, bilingual UI driven by per-app
locales, live speech with a waveform, emergency phrases that bypass the model entirely, and a "Model
& device" panel that shows the user the detected CPU and the runtime policy derived from it.

The developer experience is unusually strong: the model pipeline is reproducible from a script,
benchmarks are re-runnable tests rather than pasted screenshots, raw JSON evidence is committed
append-only, and the architecture rules are written down and enforced — including a document about
what the previous version got wrong and why.

### Evidence

| Claim | Evidence |
|---|---|
| Offline by construction | No `INTERNET` permission in the manifest; only `RECORD_AUDIO` |
| Interactive quickly | First frame in 1.2–1.9 s; **first translation in ~5.1 s**, down from 27 s |
| Correct under lifecycle stress | 58/59 functional checks pass; rotation, backgrounding, locale change, trim-release and process restart all verified |
| Privacy in release builds | **Zero** app log lines in a full release session — `logDebug` takes a lambda and is `inline`, so user speech is never even built |
| Reproducible pipeline | `cached_export.py` → `quantize_cached.py` → `verify_cache.py` (7-check gate + model-free self-check) |
| Comparable measurements | One `Stats` implementation matching the host-side parser; `bench_report.py --baseline` regression mode; `bb-bench/1` schema |
| Honest documentation | 22 documents including reverted experiments, two retracted findings, and a limitations list |
| Build from clean | `README.md` + `THIRD_PARTY_NOTICES.md` + `SUBMISSION.md` |

### Weaknesses

- **619 MB of assets**, after §3.30 removed 277 MB of duplicated weights. Still not installable from
  Play without asset delivery; a judge must side-load, and the download is a real barrier.
- **~5.1 s to first translation is better, not good.** It is behind a progress screen, and a memory
  trim makes the user pay a reload — measured at 3.5 s for a warm swap-back, not the ~10 s previously
  claimed (§3.24b).
- **No landscape layout.** Portrait is enforced because the landscape layout was found unusable. A
  responsive layout is the real fix.
- **No demo video**, and the screenshots are static.
- **A fresh clone cannot build a runnable APK.** Model binaries are correctly excluded from git, so
  the export pipeline must be run first — right for the repo, friction for a judge.
- **Tests are absent where the risk is least understood**: `speech/` and `ui/` have no JVM tests, and
  until the 2026-08-06 pass one existing test **asserted a defect as correct**.

---

## 3. Potential Impact — 15 / 20

### Justification

The target is real and specific: English↔Hindi communication with **no connectivity**, on the kind of
phone people in that situation actually carry. The primary validation device is a 2020 budget Samsung
with an Exynos 9611 — not a flagship — and it runs a 200M-parameter transformer at 667 ms for a full
sentence while staying 0.5 °C above idle. Emergency phrases deliberately bypass the model so the
safety-critical path cannot fail because a model is loading or a translation is uncertain.

Offline capability is the impact argument: disaster response, rural clinics, travel without roaming,
and any situation where sending speech to a server is impossible or unacceptable. Nothing leaves the
device, and that is enforced by the permission set.

### Evidence

| Claim | Evidence |
|---|---|
| Runs on low-end hardware | Exynos 9611, Armv8.0, 4×A73+4×A53 — full sentence in 667 ms |
| **Scales across the ecosystem** | Same APK, nine devices: 50.3 → 412.8 tok/s, long-sentence median 894 → 106 ms |
| **Both directions work** | EN→HI and HI→EN, both from verified cached INT8 exports |
| Sustainable under use | 90 consecutive translations: memory flat, +0.5 °C peak, no throttling signature |
| Genuinely offline | No network permission; models and recogniser bundled |
| Safety path is failure-proof | 32 human-translated emergency pairs, no model on the path, verified to emit no engine call |
| Speech works end to end | 2.64 s of audio → exact transcript → correct Hindi, ≈2.5 s total |

### Weaknesses

- **Distribution is unsolved** at 619 MB, and it limits reach more than any technical factor in this
  project.
- **No field validation.** No user testing with the populations described; the impact case is
  reasoned, not evidenced.
- **Recognition quality unmeasured** against real voices. The bundled Vosk Hindi model's published
  WER (14.96–39.08% by test set) is a real ceiling on the experience, and it is the number that
  decides whether people would actually use this.
- **TTS latency unmeasured**, so the speech-to-speech figure is honestly quoted as "≈2.5 s **plus**
  the system engine's start latency" rather than as one number.
- **One language pair.** IndicTrans2 supports 22 languages; adding a third direction is an enum case
  plus assets, but the asset payload is what actually bounds it.

---

## 4. WOW Factor — 20 / 25

### Justification

The wow is not a demo trick. It is that a decoder which *could not cache* now caches, on a budget
phone, with receipts a judge can re-run. Beyond that, three things very few submissions do:

**It publishes what did not work, with the numbers intact.** intra-op 8 at +90%, graph optimization
off at +13%, parallel inter-op at +10%, the intuitive "use all four big cores" thread rule at +8%
with 5× the jitter, prime-core pinning at exactly nothing (99 ms vs 99 ms), disabling prepacking at
4.6× slower, and parallelising the tokenizer parse — which looked like a 32.7% win until the
measurement was found to have warmed its own subject, and turned out to be **+6.6% worse**.

**It retracts its own findings.** `cpuArena=false` "costs 12%" was refuted by a production-path A/B —
the sweep had run the non-production load path. "`release()` does not return memory to the OS" was
wrong, and §3.25 says so in the entry rather than in an edit. "The mmap win is MT6878-exclusive" is
marked RETRACTED with the mechanism still unexplained.

**The SME investigation is the technical high point.** ORT's operator profiler **cannot** see MLAS
kernel dispatch — a negative result worth not re-deriving. So the proof is `simpleperf` (83.2% of CPU
inside `libonnxruntime.so`) plus capstone disassembly of the hottest 40-byte loop, which reads
`smopa za0.s, p2/m, p2/m, z4.b, z8.b` — KleidiAI's SME int8 outer-product kernel. Then the honest
part: priced by A/B through `mlas.disable_kleidiai` at **+4.9–9.1%**, so the device's 2× is
microarchitecture, threads and thermal headroom — **not the ISA**. Proving the kernel executes and
then refusing to credit it with the 2× is the result.

And the capability-aware runtime configures itself with **no device list anywhere in the code**, then
displays that reasoning to the user. The classifier broke twice on real silicon — a tri-cluster
Snapdragon and a uniform-IP Oryon — and both fixes are measured, with a proof that frequency ratio
provably cannot substitute for core IP.

### Evidence

| Claim | Evidence |
|---|---|
| A cache where none existed | Hand-exported `decoder_init` / `decoder_step`, 72 cache tensors, verified numerically |
| Measured, not asserted | Every claim traces to a committed JSON file and a re-runnable test |
| Negative results published | `ORT_TUNING.md`, `ARM_PLATFORM_OPTIMIZATION.md`, `OPTIMIZATION_SUMMARY.md` §3.15, §3.26, §3.28, §3.29 |
| Findings retracted when wrong | §3.20, §3.25, §3.26 |
| SME proven and priced | `S26U_EXPERIMENTS.md` §2c |
| Self-configuring runtime | `CpuCapabilities` + `ExecutionPolicy`, surfaced in the UI |
| Restraint | SME2 never claimed — and it is absent from the Armv9 device too |

### Weaknesses

- **The demo is quiet.** A 640 ms translation on a budget phone is impressive to an engineer reading
  numbers, less so to someone watching a screen. There is no side-by-side video against the uncached
  build and no live "watch it get faster" moment.
- **The Armv9 result cannot be shown live.** The S26 Ultra is no longer available. The SME evidence
  stands in `bench/results/` and can be cited but not re-demonstrated, which is the weakest position
  for the project's single most striking finding.
- **Only one modality of novelty.** The UI is a competent rebuild of the previous version, not a new
  interaction idea.
- **The wow is legible mostly in prose.** It takes reading `OPTIMIZATION_SUMMARY.md` to see it, and a
  judge with ten minutes may only see a translation app with a large download.

---

## 5. Against the challenge's six optimization categories

The four criteria above are the judging rubric; these six are what the brief says it is looking for.
Scored separately because the mapping is not one-to-one.

| Category | Standing | Headline |
|---|---|---|
| **Model size** | Strong | 1869 → 472 MB (3.96×); **APK 894 → 617 MB** (§3.30); process memory −38%; swap peak −36.7%; 451 MB file-backed |
| **Model speed** | **Strongest** | 2.12× @12 tokens; tokens/s 9.5 → 21.6; TTFT 78.5 / 107.1 / 139.5 ms; engine ready 27.0 s → ~5.1 s |
| **Model quality** | **Weak — partial** | **No fine-tuning.** What exists is output correctness: truncation **31% → 0%** measured, JSON escape decoding, quote round-trip. Quantization parity is a gate, not a gain |
| **Inference server speed** | N/A | Mobile track. No server, no network |
| **Developer experience** | Strong | Reproducible export → 7-check verify gate → re-runnable benchmarks → `--baseline` regression mode; 22 docs; negative results published |
| **Arm-specific** | Strong | HWCAP + cpufreq-derived policy, no device list in code; nine devices Armv8.0 → Armv9; SME dispatch proven by disassembly and priced. **But** no EP/kernel selection acts on the detected flags |

**Arm Performix was not used and not evaluated.** The challenge text names it. The method that stands
in its place is described in `SUBMISSION.md`; adopting Performix is named as a next step rather than
passed over.

---

## 6. What would raise the score most

Ranked by points per unit of work, honestly. The previous revision's top three — export HI→EN, cut
the 27 s, validate on Armv8.2+ silicon — are all **done**, which is most of why this revision scores
higher.

1. **Finish release engineering** (+2–3, Implementation). Real keystore from
   `~/.gradle/gradle.properties`, R8 on with keep rules for `ai.onnxruntime.**` and `org.vosk.**`,
   `versionCode` from CI. Re-run `BenchmarkSuiteTest` afterward — R8 is a real behaviour change on the
   inference path. Nothing ships without this, and it is hours of work.
2. **A 60-second side-by-side demo video**, uncached vs cached (+2, WOW). The single cheapest fix for
   the project's biggest presentational weakness — that its best results are only legible in prose.
3. **Measure WER against real voices** (+1–2, Impact). It is the number that decides whether anyone
   would use this, and it is currently unmeasured on both directions.
4. **Solve distribution** — Play Asset Delivery or a first-run model download (+1–2, Impact). 619 MB
   bounds reach more than any technical factor, and §3.30 took the easy 277 MB already.
5. ~~**Close §3.21 with a measurement**~~ — **DONE 2026-08-12 (§3.37).** The claim under the clamp
   holds: on the SM-M315F production path the shipping arm is the fastest measured on both sentence
   lengths, and 4 threads loses pinned (+8.2% / +26.9%) and unpinned (worst jitter in the sweep). The
   numeric bound stays INFERRED — no obtainable device derives 4.
6. ~~**Q1: slice the last position inside the exported `decoder_init`**~~ — **DONE and NOT SHIPPED
   (§3.31).** It was carried here as "the largest remaining inference lever"; it is not one. Greedy
   seeds a one-token prefix and every later token takes `decoder_step`, so `decoder_init` runs once
   per translation at `dec_len = 1` — the case where the slice removes exactly zero work. Measured
   49.6 ms against a 49.0 ms control. The graph exists and passes the 7/7 gate; it is held for beam
   (Q6), the only workload that calls `decoder_init` with a long prefix.
7. **Fine-tune, or measure quality properly** (+1–2). A BLEU/chrF run against a FLORES subset would
   at least give the model-quality category a number, even without fine-tuning.
