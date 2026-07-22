# Parallel ONNX Session Initialization (Phase 11C)

After Phase 11B removed the tokenizer bottleneck, ONNX Runtime session creation was 74% of what
remained. This phase loads the three graphs concurrently instead of one after another.

**Result: engine ready 16,584 ms → 10,502 ms warm (−36.7%), 17,627 ms → 11,287 ms cold (−36.0%).
Translation output byte-identical, no leaks, no surviving threads. The cost is +126 MB of peak
memory during load, measured and reported below.**

All measurements on the **SM-M315F** (Exynos 9611, 4×A73 + 4×A53, Armv8.0-A, 6 GB RAM, Android 12),
debug build.

---

## 1. Root cause

`OnnxModels.init` built the three sessions one after another, and the code said so explicitly
("Sequential, not parallel: parallel load is a startup optimisation this phase does not do").

Phase 11A established why that ordering is pure loss:

| Finding | Measurement |
|---|---|
| Disk I/O is a rounding error in session creation | 79 ms read vs 2,619 ms create (encoder); 150 vs 4,989 (decoder_init) |
| Graph optimisation passes dominate | `NO_OPT` is 52–61% faster than the default `ALL_OPT` |
| Load time is single-threaded work | `intra=1` vs `intra=2` changes nothing (2,619 → 2,498 ms) |
| The three loads are independent | Probe: serial 12,285 ms vs 6,258 ms on three threads (**1.96×**) |

Three independent, single-threaded, CPU-bound tasks running in sequence on a four-big-core CPU.

---

## 2. Why parallel loading is safe here

Not "it seemed to work" — each claim below is a property of the code or a measured result.

**No shared mutable state between the tasks.** Each task owns its own `File`, its own
`FileOutputStream`, its own `SessionOptions` (built inside the worker, since ORT reads options during
`createSession`), and produces one `OrtSession` that only it touches.

**No dependency between the sessions.** Nothing flows from one graph's construction into another's.
The only cross-session logic — the cache-contract check comparing `decoder_step`'s past inputs against
`decoder_init`'s present outputs — runs on the constructing thread *after* all three have joined, and
is unchanged.

**The one shared object is created before any thread starts.** `env` is an `OrtEnvironment`, a
process-wide singleton by ONNX Runtime's contract, and it is a property initialiser — it runs before
the `init` block, so there is no race to create it. Workers only *use* it. `OrtEnvironment` and
`OrtSession` creation are documented as thread-safe in the ORT Java API, and this is the same pattern
the Phase 11A probe exercised successfully before any production code changed.

**No duplicated loads.** The engine is constructed exactly once per direction, inside
`BhashaBridgeApp.translator`'s `@Synchronized getOrPut` — the concurrency added here is *within* one
construction, not across several.

**Publication is safe.** The three `val` fields are assigned on the constructing thread, from results
handed back through `Future.get()`, which establishes a happens-before edge between each worker and
the constructor. Consumers see fully-built sessions because the constructor does not return until
every future has completed.

### ORT thread-affinity

None observed, and none required. Sessions are created on `bb-session-load` worker threads and then
used for inference on a completely different thread (`bb-mt`, the ViewModel's single MT dispatcher)
across 90 benchmark translations and every functional test, with byte-identical output. ONNX Runtime's
C API does not bind a session to its creating thread; the Java wrapper holds a native handle with no
thread-local state. Had affinity been required, the sessions would have failed on first use on the MT
thread — they did not.

---

## 3. Implementation

`OnnxModels.init` now submits one `Callable` per graph to a three-thread pool and blocks until all
three finish:

```kotlin
val loads = loadSessionsConcurrently(
    context,
    listOf(encAsset to "encoder", initAsset to "decoder_init", stepAsset to "decoder_step"),
)
encoder     = loads.getValue("encoder").session
decoderInit = loads.getValue("decoder_init").session
decoderStep = loads.getValue("decoder_step").session
```

The loader's contract, in the order the requirements were stated:

- **Independent tasks** — `Executors.newFixedThreadPool(3)`, one `Callable` per graph, each running
  `loadSession` (verify → extract if absent → `createSession`) end to end on its own thread.
- **Exception propagation** — every future is awaited before anything is thrown, so no task is left
  running against a half-constructed object. `ExecutionException` is unwrapped so callers see the
  same `OrtException`/`IOException` a serial load produced, wrapped in one `IllegalStateException`
  that names the stage. `InterruptedException` re-sets the interrupt flag.
- **Deterministic ownership** — results are keyed by label and assigned to their `val` by name, not
  by completion order. Order of completion cannot swap a session into the wrong field.
- **Deterministic cleanup** — if any task failed, every session that *did* load is closed before the
  exception leaves the method. Without that, a partial failure would strand hundreds of MB of native
  memory with no owner to release it — LESSONS_FROM_V3 L2 in a new disguise.
- **No races** — see §2. The pool is shut down in a `finally` on every path, and its threads are
  daemons, so nothing can outlive the constructor even if shutdown were somehow skipped.
- **"Engine ready" only when all three succeed** — the constructor blocks on all three futures, so
  `BhashaBridgeApp.translator` cannot return, and the ViewModel cannot report ready, until every
  session exists.

Instrumentation had to move with the work. A `Metrics` run is thread-confined by design (R6.3), so
`Metrics.stage` from a worker would find no active run. Each worker now records `System.nanoTime`
deltas into its result, and the constructing thread replays them as counters (`verify_us:*`,
`extract_us:*`, `create_us:*`) alongside one `sessions:parallel` wall-clock stage. Startup lines
therefore still carry per-graph numbers.

**Not touched:** models, graphs, quantization, tokenizer, decoder, KV-cache, execution policy, ORT
tuning, runtime algorithms. No graph optimisation setting changed; `ExecutionPolicy.current` supplies
exactly the same `OrtTuning` it did before.

---

## 4. Correctness validation

| Check | Method | Result |
|---|---|---|
| Identical translation | `MtEngineInstrumentedTest` parity assertions | `'Water.' => 'पानी ।' \| 'पानी ।'`; `'Hello, how are you?' => 'हैलो , आप कैसे हैं ?'` ✓ |
| Identical benchmark output | 3 sentences × 30 runs | all three byte-identical to every phase since 6D ✓ |
| Identical tokenizer output | `StartupProbeTest#probeTokenizerLoad` | 122,672 entries, `identical=true` ✓ |
| Identical KV-cache behaviour | Cache contract re-checked on every construction | **72 tensors** (18 layers × 4), ordering identical across 3 cycles ✓ |
| Identical decoder output | `steps == tokens` and identical strings across 90 translations | ✓ |
| Session identity / ownership | 3 distinct sessions; `decoder_step` still exposes no `encoder_hidden_states` | ✓ |
| Existing unit tests | `./gradlew testDebugUnitTest` | **20/20 pass** ✓ |
| Existing instrumented tests | `ParallelSessionLoadTest`, `MtEngineInstrumentedTest`, `StartupProbeTest` | **4/4 pass** ✓ |

---

## 5. Thread safety — demonstrated

`ParallelSessionLoadTest` runs three full construct/release cycles and asserts the properties
concurrency could break:

| Property | Evidence |
|---|---|
| **No races** | Cache contract 72 tensors with identical ordering on every cycle; three distinct sessions each time; correct graph in each field (`encoder` has `input_ids`, `decoder_step` has no `encoder_hidden_states`) |
| **No deadlocks** | Construction completed in **6,139 / 6,482 / 6,049 ms**; a deadlock would surface as an instrumentation timeout |
| **No resource leaks** | `Debug.getNativeHeapAllocatedSize()` drift after 3 construct/release cycles: **0 MB** (a single leaked session would be 75–204 MB) |
| **Deterministic shutdown** | `bb-session-load` threads alive after each constructor returns: **0, 0, 0**, and 0 after all cycles |

---

## 6. Before / after benchmarks

Method: 20 warm startups (`am force-stop` → `am start` → capture the `engine_init` run) and 5 cold
startups (extracted `.onnx` files deleted via `run-as` first, verified 0 present before each launch).
Baseline is Phase 11B's 20-run warm set and 3-run cold set on the same device.

### 6.1 Warm start (n = 20)

| Stage | 11B serial | 11C parallel | p95 | stdev | Δ |
|---|---|---|---|---|---|
| tokenizer group | 4,188.2 | 4,220.6 | 4,278.7 | 258.7 | +0.8% (untouched) |
| **ONNX sessions (wall)** | **12,311.3** | **6,289.2** | 6,458.1 | 293.1 | **−48.9% (1.96×)** |
| `cache_contract` | 7.3 | 7.6 | 37.2 | 10.5 | — |
| **engine_init total** | **16,584.3** | **10,501.6** | **10,637.1** | **322.4** | **−36.7% (1.58×)** |

Range: 9,400–10,753 ms. Standard deviation is 3.1% of the median.

### 6.2 Serial vs parallel session creation

Per-graph `createSession` time, measured inside each worker:

| Graph | 11B serial | 11C parallel (under contention) | Δ |
|---|---|---|---|
| encoder | 2,557.7 | 3,147.1 | +23.0% |
| decoder_init | 5,047.5 | 6,163.5 | +22.1% |
| decoder_step | 4,705.2 | 5,949.8 | +26.5% |
| **sum of the three** | 12,310.4 | 15,260.4 | +24.0% |
| **wall clock** | **12,311.3** | **6,289.2** | **−48.9%** |

Each individual load got ~24% *slower* — three graph optimisers competing for cores, memory bandwidth
and cache. The honest speedup is therefore **1.96×**, not the 2.43× that dividing the summed CPU time
by wall clock would suggest. That 1.96× matches the Phase 11A prototype (12,285 → 6,258 ms) almost
exactly.

### 6.3 Cold start (n = 5)

| | 11B serial (n=3) | 11C parallel (n=5) |
|---|---|---|
| **Total** | 17,627 | **11,286.7** (p95 11,437.2, stdev 126.2) |
| — sessions incl. extraction (wall) | ~14,000 | 7,809.3 (p95 7,924.5, stdev 80.3) |
| — extraction, summed per thread | 1,806 | ~5,050 |

**−36.0%.** Extraction is now concurrent too, since it lives inside each worker's `loadSession`. The
summed extraction time nearly triples (three 200 MB writes competing for one eMMC), but it overlaps
with graph optimisation, so cold start still improves by 6.3 s.

### 6.4 No regression in runtime

| Sentence | Phase 10 | 11B | **11C** |
|---|---|---|---|
| "Water." (2 tok) | 163.1 | 163.0 | **162.7** |
| "Hello, how are you?" (6 tok) | 364.5 | 362.4 | **372.5** |
| "The weather…" (12 tok) | 667.9 | 669.9 | **668.8** |

Outputs identical. The 6-token sentence is 2.8% above the 11B run and 2.2% above the tightest prior
value (362.4–372.5 across four phases); its stdev also rose (18.4 → 23.5 ms). There is no mechanism
for this change to affect inference — loading finishes entirely before any translation starts, and the
sessions are used from the same single MT thread as before — so this is read as run-to-run variation on
a device that had been under continuous load for hours, not as a regression. It is recorded rather
than smoothed away.

### 6.5 Memory — the real cost

| Measurement | Serial | Parallel | Δ |
|---|---|---|---|
| **Peak PSS during load** | **604.7 MB** | **731.1 MB** | **+126.4 MB (+20.9%)** |
| Steady state after 90 translations | 629.0 MB | 640.8 MB | +11.8 MB (+1.9%) |

Both peaks were measured the same way on the same device: force-stop, launch, sample `TOTAL PSS`
every second through the load, take the maximum — the serial figure from a temporary local build with
the parallel loader stashed out, so the comparison is like-for-like.

Three graph optimisers holding their working sets simultaneously costs ~126 MB more at the peak. That
is the trade the speedup is bought with. On this 6 GB device it is comfortable (peak 731 MB against
~2.2 GB available), and steady-state memory is unchanged within noise — but on a 2 GB device this is
the number that would decide whether parallel loading is viable. Loading two graphs at a time instead
of three would recover most of the peak for part of the speedup; that is future work, not this phase.

---

## 7. Combined startup improvement

Warm start, process fork → engine ready:

| | 11A baseline | after 11B | after 11C |
|---|---|---|---|
| tokenizer | 12,675 | 4,188 | 4,221 |
| ONNX sessions | 11,864 | 12,311 | **6,289** |
| other (verify, contract) | 123 | 123 | ~8 |
| **engine_init** | **24,662** | **16,584** | **10,502** |
| pre-engine (fork → construction) | 1,194 | 1,194 | 1,194 |
| **fork → engine ready** | **25,856** | **17,778** | **≈11,696** |

```
11A  ████████████████████████ tokenizer 12,675   ███████████████████████ sessions 11,864   = 24,662 ms
11B  ████████ tokenizer 4,188                    ████████████████████████ sessions 12,311  = 16,584 ms
11C  ████████ tokenizer 4,221                    ████████████ sessions 6,289               = 10,502 ms
```

**Cumulative: 24,662 → 10,502 ms warm (−57.4%, 2.35×) and 27,331 → 11,287 ms cold (−58.7%, 2.42×).**

The user-visible number Phase 10 flagged as the project's worst — "27 s to first translation" — is now
**11.3 s on a first install and ~11.7 s from process start on every later launch**, with no change to
any model, graph, or translation result.

---

## 8. Remaining startup bottlenecks

**None of these is implemented.** Phase 11C stops here by instruction.

| # | Bottleneck | Now | Evidence | Potential |
|---|---|---|---|---|
| 1 | **ORT graph optimisation, still redone every launch** | ~6,289 ms wall (59.9% of engine_init) | `NO_OPT` is 52–61% faster than `ALL_OPT` (11A §3.2) | Serialise optimised graphs offline (`optimized_model_filepath`) and load with `NO_OPT`: potentially ~3 s. Requires re-verifying parity against a new artifact |
| 2 | **Tokenizer first-parse JIT warm-up** | ~2,551 ms of the 4,221 | `dict.SRC.json` parses at 0.27 MB/s while `dict.TGT.json`, same loop moments later, hits 2.9 MB/s | Baseline profiles (ART AOT) or a binary vocabulary format |
| 3 | **Peak memory during parallel load** | +126 MB | §6.5 | Load two graphs at a time; trades some speedup for headroom on low-RAM devices |
| 4 | **Reverse-index construction** | 602 ms | 122,672-entry inverted map built eagerly | Build lazily — only `decode()` needs it |
| 5 | **Asset extraction** (cold only) | ~1.5 s of wall time | 472 MB at ~262 MB/s serial, contended when parallel | Memory-map the assets instead of copying |
| 6 | **Pre-engine cost** | 1,194 ms | fork → onCreate 360 ms, then Activity + ViewModel + dispatch | Little to win; the screen is interactive at ~1.4 s regardless |

The two remaining second-scale wins are both in ONNX Runtime's graph pipeline, and both mean changing
what ships rather than how it is loaded — which is exactly why they were out of scope here.
