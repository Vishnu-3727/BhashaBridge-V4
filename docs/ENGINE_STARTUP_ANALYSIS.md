# Engine Startup Analysis (Phase 11A)

Phase 10 measured "27 s to first translation" as a single number. This phase instruments every stage
of startup and explains where that time actually goes.

**Headline: it is not the models. Half of startup is a JSON parser.**

Of ~25 s from process fork to a usable engine, **49% is the tokenizer reading two dictionary files**
and **46% is ONNX Runtime creating three sessions**. Asset extraction — the thing that looked
expensive because 472 MB moves — costs 1.8 s, once, and only on first run.

Nothing was optimised in this phase. Every number below is measured on the **SM-M315F** (Exynos 9611,
4×A73 + 4×A53, Armv8.0-A, Android 12), debug build with `Metrics` active.

---

## 1. How it was measured

`Metrics` stage marks were added along the startup path — inline and `BuildConfig.DEBUG`-gated, so a
release build contains none of them and the load path is unchanged:

| Stage | Instrumented in | Mark |
|---|---|---|
| Process fork → first app code | `BhashaBridgeApp.onCreate` | `Process.getStartUptimeMillis()` delta |
| Engine construction (whole) | `BhashaBridgeApp.translator` | run `engine_init` |
| Tokenizer, per dictionary | `Tokenizer.load` | `tokenizer:src_dict`, `tokenizer:tgt_dict`, `tokenizer:reverse_index` |
| Asset presence check + ORT env | `OnnxModels.resolveAsset` | `verify:<graph>` |
| Asset extraction | `OnnxModels.resolveAsset` | `extract:<graph>` |
| Session creation, per graph | `OnnxModels.init` | `session:<graph>` |
| Cache-contract check | `OnnxModels.init` | `cache_contract` |
| Vosk model load | `VoskModels.model` | run `vosk_load` (`vosk:unpack`, `vosk:native_load`) |

Causes were then isolated with `StartupProbeTest` (androidTest, measurement only — it compares
alternatives side by side and changes no production code).

---

## 2. Timing breakdown

### 2.1 Warm start — the normal case (assets already extracted)

Two consecutive runs: **26,358 ms** and **24,662 ms**. The second is tabulated; both agree on
structure.

| Stage | ms | % of engine_init | % of fork→ready |
|---|---|---|---|
| `tokenizer:tgt_dict` | **9,204.7** | 37.3% | 35.6% |
| `session:decoder_init` | **4,859.5** | 19.7% | 18.8% |
| `session:decoder_step` | **4,509.9** | 18.3% | 17.4% |
| `tokenizer:src_dict` | **2,861.2** | 11.6% | 11.1% |
| `session:encoder` | **2,494.5** | 10.1% | 9.6% |
| `tokenizer:reverse_index` | 609.2 | 2.5% | 2.4% |
| `verify:encoder` (includes the ORT native library load) | 114.3 | 0.5% | 0.4% |
| `cache_contract` | 7.9 | 0.0% | 0.0% |
| `verify:decoder_init`, `verify:decoder_step` | 0.4 | 0.0% | 0.0% |
| **engine_init total** | **24,661.8** | 100% | 95.4% |

Grouped:

| Group | ms | % |
|---|---|---|
| **Tokenizer** | **12,675** | **51.4%** |
| **ONNX session creation** | **11,864** | **48.1%** |
| ORT env + asset verification | 115 | 0.5% |
| Cache contract check | 8 | 0.0% |

Add the pre-engine cost and the full picture is:

| Phase | ms | % of fork→ready |
|---|---|---|
| Process fork → `Application.onCreate` (ART, class loading, native libs) | 360 | 1.4% |
| `onCreate` → engine construction starts (Activity, ViewModel, dispatch) | 834 | 3.2% |
| Engine construction | 24,662 | 95.4% |
| **Total fork → engine ready** | **25,856** | **100%** |

The screen itself is interactive at ~1.4 s (`am start -W` TotalTime 1361 ms); everything after that
runs behind the loading overlay.

### 2.2 Cold start — first run after install

**27,331 ms**, i.e. **+2,669 ms** over warm. The extra is asset extraction, paid once:

| Extra stage | ms | Bytes | Throughput |
|---|---|---|---|
| `extract:encoder` | 205.0 | 74.9 MB | 365 MB/s |
| `extract:decoder_init` | 817.1 | 203.6 MB | 249 MB/s |
| `extract:decoder_step` | 783.7 | 194.0 MB | 248 MB/s |
| **total extraction** | **1,806** | **472.5 MB** | **~262 MB/s** |

Extraction is 6.6% of a cold start and 0% of every later start. It is not the problem.

### 2.3 Flame-style timeline (warm start, ms from process fork)

```
0                5k              10k             15k             20k            25.9k
├────────────────┼───────────────┼───────────────┼───────────────┼───────────────┤
█ 0–360      fork → Application.onCreate ............................ 360 ms  1.4%
▓ 360–1194   Activity + ViewModel + dispatch to MT thread ........... 834 ms  3.2%
│
│            ENGINE_INIT (24,662 ms) ─────────────────────────────────────────────
├─ 1194 ─────────────────────────────────────────────┐
│  ████████████ tokenizer:src_dict    2,861 ms 11.6% │  dict.SRC.json  0.65 MB
│  ██████████████████████████████████████ tokenizer:tgt_dict
│                                     9,205 ms 37.3% │  dict.TGT.json  3.39 MB
│  ██ tokenizer:reverse_index           609 ms  2.5% │
├─ 13,869 ───────────────────────────────────────────┤  ← half of startup, 4 MB of JSON
│  ▏ verify + ORT native load           115 ms  0.5% │
│  ██████████ session:encoder         2,494 ms 10.1% │  74.9 MB  → 30.0 MB/s
│  ████████████████████ session:decoder_init
│                                     4,860 ms 19.7% │  203.6 MB → 41.9 MB/s
│  ███████████████████ session:decoder_step
│                                     4,510 ms 18.3% │  194.0 MB → 43.0 MB/s
│  ▏ cache_contract                       8 ms  0.0% │
└─ 25,856  ENGINE READY ─────────────────────────────┘

(cold start inserts extract:* totalling 1,806 ms between verify and session, once per install)
```

---

## 3. Likely causes — measured, not guessed

### 3.1 Tokenizer: an unbuffered reader, one `read()` per character

`Tokenizer.parseFlatIntDict` walks the JSON character by character from an `InputStreamReader` with
no buffer between it and the asset stream. `StartupProbeTest.probeTokenizerLoad` runs the **same
parser** over the **same file** with only the reader changed:

| Path | ms | vs production |
|---|---|---|
| Raw byte read of the asset (no decode, no parse) — the I/O floor | **18** | — |
| `parseFlatIntDict(InputStreamReader(...))` — **the production path** | **9,951** | 1.00× |
| `parseFlatIntDict(BufferedReader(InputStreamReader(...), 64 KB))` | **1,082** | **9.2× faster** |

dict.TGT.json is 3,390,440 bytes.

- The file itself takes **18 ms** to read. I/O is not the cost.
- The parse *logic* takes **~1.08 s** — that is the real work: ~3.4 M character comparisons, a
  `StringBuilder` per token, and ~122 k `HashMap` insertions.
- The remaining **~8.9 s (89%)** is pure per-character call overhead: `read()` on an unbuffered
  reader crosses into `StreamDecoder` for every single character.

Production throughput is **0.23–0.37 MB/s**. The asset copy in the same startup runs at **250–365
MB/s**. The tokenizer is three orders of magnitude slower than the file copy, on files 100× smaller.

Two aggravating details found while probing:

- **The dictionaries are DEFLATE-compressed in the APK.** `assets.openFd()` throws on them;
  `androidResources.noCompress` covers only `onnx`, `bin`, `pb`. So each character is also being
  pulled through an inflater. (Inflation itself is cheap — it is inside the 18 ms floor — but it
  removes any chance of a zero-copy read.)
- **`dict.SRC.json` is disproportionately slow**: 0.65 MB in 2,861 ms = 0.23 MB/s, versus 0.37 MB/s
  for the 3.39 MB file. It is parsed first, so it also pays the JIT warm-up for the parse loop.

### 3.2 ONNX session creation: graph optimisation, not I/O

`StartupProbeTest.probeSessionCreation` creates the same sessions with different options:

| Graph | Disk read | Production (`ALL_OPT` default) | `NO_OPT` | `intra=1` |
|---|---|---|---|---|
| `encoder_int8.onnx` (74.9 MB) | **79 ms** | 2,619 ms | **1,024 ms** (−61%) | 2,498 ms |
| `decoder_init_int8.onnx` (203.6 MB) | **150 ms** | 4,989 ms | **2,408 ms** (−52%) | 4,950 ms |

- **Disk I/O is ~3%** of session creation. The files are already in page cache and read at ~1 GB/s.
- **Graph optimisation passes are ~50–60%** of it. ONNX Runtime's default `ALL_OPT` runs constant
  folding, fusion and layout transforms over a graph with 145 `MatMulInteger` nodes plus 109
  `DynamicQuantizeLinear` nodes, every single launch, producing the same result each time.
- **Thread count is irrelevant to load time** (2,619 → 2,498 ms at `intra=1`), which confirms the
  cost is single-threaded graph work, not parallel compute.
- The remaining ~40% is protobuf deserialisation and weight materialisation, which scales with file
  size (30–43 MB/s across the three graphs).

### 3.3 The three sessions are loaded sequentially

`OnnxModels.init` creates encoder, then `decoder_init`, then `decoder_step`, one after another — a
choice the code documents as deliberate ("parallel load is a startup optimisation this phase does not
do"). The probe measures what that costs:

| | ms |
|---|---|
| Serial (production order) | **12,285** |
| Same three graphs on three threads | **6,258** |

**1.96×** on a 4-big-core CPU. The three graphs are independent — no data flows between them at load
time — so the serialisation is purely structural.

### 3.4 Asset extraction is not a problem

472 MB at ~262 MB/s, once per install, 1.8 s. Every later launch pays only the `File.exists()` checks:
**0.2 ms each** (the 114 ms attributed to `verify:encoder` is really the first-touch cost of
`OrtEnvironment.getEnvironment()` loading the ONNX Runtime native library, which lands in that bucket
because it is the first mark after the tokenizer).

### 3.5 There is no warm-up inference — and none is needed

The app performs no warm-up pass; `MtEngine` has no `warmUp()` (v3.4.1 had one). The cost of not
having it, extracted from the Phase 10 benchmark's discarded warm-up rounds:

| Sentence | Very first inference | Steady-state median | Penalty |
|---|---|---|---|
| "Water." (2 tokens) | 263.5 ms | 163.1 ms | **+100.4 ms** (1.62×) |
| "Hello, how are you?" (6 tokens) | 364.9 ms | 364.5 ms | +0.4 ms (1.00×) |
| "The weather…" (12 tokens) | 657.5 ms | 667.9 ms | −10.4 ms (0.98×) |

One first call pays ~100 ms of JIT and allocator warm-up; by the second call it is gone. Against a
25 s load, adding a warm-up pass would make startup *worse*, not better.

### 3.6 Vosk

Measured in Phase 10: **1,336 ms** for the English model on first mic use, off the startup path
entirely (lazy, only when the microphone is first used). `VoskModels` is now instrumented to split
`vosk:unpack` from `vosk:native_load`; that split has not been captured in a run yet.

---

## 4. Percentage contribution — summary

Warm start, fork → engine ready (25,856 ms):

```
tokenizer  ██████████████████████████████████████████████████  49.0%   12,675 ms
sessions   ██████████████████████████████████████████████      45.9%   11,864 ms
pre-engine ████                                                 4.6%    1,194 ms
verify/env ▏                                                    0.4%      115 ms
contract   ▏                                                    0.0%        8 ms
```

Cold start adds 1,806 ms of extraction (6.6% of 28,525 ms), once.

---

## 5. Optimisation opportunities

Ranked by measured recoverable time. **None of these is implemented — Phase 11A explicitly does not
optimise.** Each carries the number that justifies it and the risk that has to be handled.

| # | Opportunity | Measured basis | Est. recovery | Risk |
|---|---|---|---|---|
| 1 | **Buffer the dictionary reader** | Same parser, `BufferedReader(64 KB)`: 9,951 → 1,082 ms on the 3.39 MB file | **~10.8 s** across both dicts | Very low — one wrapper, parser untouched, output identical by construction |
| 2 | **Load the three sessions in parallel** | Probe: 12,285 → 6,258 ms | **~5.9 s** | Low–medium — three ORT sessions built concurrently; memory peaks higher during load |
| 3 | **Pre-optimise the graphs offline** (`optimized_model_filepath`, then load with `NO_OPT`) | `NO_OPT` is 52–61% faster: encoder 2,619 → 1,024, decoder_init 4,989 → 2,408 | **~6 s** | Medium — the shipped artifact changes, so parity must be re-verified; ORT's optimised format is version-sensitive |
| 4 | **Replace the JSON dictionaries with a binary/packed vocab** | Parse logic alone still costs ~1.08 s after buffering; the I/O floor is 18 ms | **~1 s** on top of #1 | Medium — new asset format, needs a pipeline step and its own verification |
| 5 | **Don't block the UI on the full engine** — the encoder alone is enough to accept input; `decoder_step` is not needed until token 2 | encoder is 2,494 ms of the 11,864 ms session cost | Perceived, not actual | Medium — changes the readiness contract the ViewModel exposes |
| 6 | **Stop compressing the dictionaries** (`noCompress += "json"`) | `openFd` fails today; the inflate sits inside the 18 ms floor | <100 ms | Very low, but small payoff |
| 7 | **Skip the copy, load models from a memory-mapped asset** | Extraction is 1,806 ms once; ORT needs a path | ~1.8 s, first run only | Medium — ORT Java requires a file path or byte array |

Combining #1, #2 and #3 targets roughly **22 s of the 25 s**, but they interact: parallel loading and
offline pre-optimisation both attack the same session cost and would not simply add. The honest
sequencing is #1 first — it is the largest single win, the lowest risk, and it needs no change to any
model artifact.

---

## 6. What this changes about the Phase 10 conclusion

Phase 10 recorded "27 s engine load" as the worst user-facing number and attributed it, implicitly, to
the size of the INT8 models. That was wrong in an interesting way:

- The models are **not** the dominant cost. All three graphs together deserialise and optimise in
  ~11.9 s, and their bytes read off disk in 230 ms.
- **A 4 MB JSON parse is 51% of engine startup** — more than 472 MB of neural network weights.
- The single highest-value fix in the whole project right now is a `BufferedReader`.

Nothing in this phase changed behaviour. Instrumentation is debug-only; the release build is
byte-identical in its load path.
