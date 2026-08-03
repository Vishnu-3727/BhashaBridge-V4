# Arm Platform Optimization (Phase 8)

Makes the runtime **capability-aware** instead of device-specific. Phase 7 hand-picked
`intra_op = 2` for the SM-M315F — a constant. Phase 8 detects the CPU and *derives* the
same kind of config from what it finds, so one binary configures itself across the Arm
ecosystem, from an Armv8.0 A53 to an Armv9 SME2 core.

No change to the decoder, tokenizer, cache, model, or quantization — only how the ORT
sessions are configured.

## Architecture

```
CpuCapabilities.detect()      ExecutionPolicy.select(caps)      OnnxModels / MtEngine
   (kernel /proc, /sys)  ─────►   (caps → OrtTuning)      ─────►  createSession(options)
        ISA + topology              threads, arena                 (default = the policy)
```

- **`CpuCapabilities`** — a snapshot of what the running core can do, read from the kernel.
- **`ExecutionPolicy`** — pure function `caps → OrtTuning`, plus a cached `current`.
- **`OnnxModels` / `MtEngine`** default their `OrtTuning` to `ExecutionPolicy.current`. Nothing
  device-specific survives in the code; the SM-M315F value falls out of the rules, it is not written.

## Capability detection

`CpuCapabilities.detect()` reads two kernel sources, both world-readable, no Context, no NDK:

- **ISA features** — the `Features` line of `/proc/cpuinfo` (the kernel's HWCAP names):
  `asimd`→NEON, `asimdhp`/`fphp`→FP16 arithmetic, `asimddp`→Dot Product, `i8mm`→Int8 MatMul,
  `sve`/`sve2`→SVE, `sme`/`sme2`→SME.
- **Topology** — per-core max frequency from `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`;
  the cores at the top frequency are the performance (big) cluster, the rest are efficiency (little).
- **Architecture label** — `/proc/cpuinfo` reports only the base (`CPU architecture: 8`), so the minor
  version is inferred from features: SME2/SVE2 ⇒ Armv9, i8mm ⇒ v8.6, dotprod ⇒ v8.2, else v8.0.

Every read is best-effort: a missing file degrades to a safe default (feature absent, one cluster),
never a crash.

**Detected on the SM-M315F (validation device):**

```
ARMv8.0  cores=8 (perf=4, eff=4)
neon=true  fp16=false  dotprod=false  i8mm=false  sve=false  sve2=false  sme=false  sme2=false
```

Correct: Exynos 9611 is 4×Cortex-A73 (big) + 4×Cortex-A53 (little), Armv8.0-A, NEON only — no
dot-product or i8mm acceleration, which is exactly what the detector reports.

## Runtime policy

`ExecutionPolicy.select(caps)` derives an `OrtTuning`:

| knob | rule | on SM-M315F | why it is a rule, not a constant |
|---|---|---|---|
| intra_op threads | `(performanceCores / 2)` clamped to `[1,2]` | 4 perf → **2** | small int8 GEMMs are latency-bound and saturate parallelism fast; half the big cluster hits the Phase-7 optimum, and no device has ever measured more than 2 as better (see below) |
| inter_op | sequential | — | one latency-bound stream, not a throughput fan-out |
| CPU arena | off | off | Phase 7: the arena pool is pure overhead for this steady single-stream workload (−37% memory, no latency cost) — a workload property, not a device one |
| int8 kernels | (not set) | NEON | ORT/MLAS dispatches SDOT/i8mm/SME on HWCAP at runtime; nothing to toggle from Java |

### Why half the performance cluster

The naive rule "threads = all performance cores" was tried first and **regressed** on the SM-M315F:

| policy | Weather (12 tok) median | p95 | stdev |
|---|---|---|---|
| threads = 4 (all big) | 719.0 ms | 869.4 | 88.8 |
| threads = 2 (half big) | **667.2 ms** | **686.5** | **18.4** |

This matches Phase 7's intra-op sweep exactly (2 was the sweet spot; 4 jittered; 8 collapsed): past
~2 threads, big.LITTLE scheduler sync/migration overhead outweighs the parallelism these small
per-token GEMMs can use. Half the big cluster encodes that as a portable rule.

### Why the clamp is `[1,2]` and not `[1,4]`

It was `[1,4]` originally, on the untested reasoning that an 8-big flagship should scale to 4 threads.
Entry #9 is that flagship — the SM-S948B's eight uniform Oryon cores classify as `perfCores = 8`, so
the rule derived 4 — and the production-path A/B measured it as a regression
(`bench/results/cross-device/S26U_EXPERIMENTS.md` §2b, `ProductionThreadSweepTest`, real shipping load
path with `optCache` on, 7 configs × 3 rotated rounds, n=45 per config, all arms parity-exact):

| config | long median | stdev | short median |
|---|---|---|---|
| `intra1` | 98 ms | 3.9 | 27 ms |
| **`intra2`** | **99 ms** | **3.0** | **27 ms** |
| `intra4` (old rule) | 104 ms | 4.2 | 31 ms |
| `intra6` | 116 ms | 8.2 | 38 ms |
| `intra8` | 150 ms | 14.9 | 39 ms |

−4.8% on the long sentence and −12.9% on the short one, and short utterances are the common case for
an emergency-phrase translator. Across nine devices spanning Armv8.0 → ARMv9 and four silicon vendors,
**no entry has ever measured 4 threads as the optimum**, and the only topology that could derive 4
measured it as a loss. Eight of the nine already derive 1 or 2, so tightening the clamp changes
behaviour on that one part alone. `ExecutionPolicyTest` pins the derivation so the bound cannot move
silently. The exact count should still be re-validated per new topology — the heuristic is the
default, not a proof for cores no entry has measured.

## Memory strategy

Most of the memory discipline is architectural, established in earlier phases and simply confirmed here:

- **Session reuse** — `BhashaBridgeApp` owns one `MtEngine` per direction at process scope (R4.4/R4.5);
  the three sessions load once and serve every translation. No per-call session creation.
- **Lazy allocation** — engines are built lazily via `translator(direction)` on first use, and released
  on `onTrimMemory(COMPLETE)`.
- **Persistent tensors** — within a translation, `encoder_hidden_states` and the attention mask are
  created once and reused across every decode step; the KV-cache `present` tensors persist step-to-step
  (each run's result is fed as the next `past`, closed only when superseded).
- **Allocator strategy** — CPU arena disabled by policy: **981 → 605–620 MB PSS (−37%)**, no latency cost.
- **Tensor reuse (known ceiling)** — the one per-step allocation left is `OnnxTensor.value` boxing the
  logits row; it lives in the frozen cache path, so a zero-copy `getFloatBuffer` rewrite is deferred, not
  done here.

## Scalability to newer Arm CPUs

The same binary gets faster on better silicon without a code change:

- **Threads scale with the detected big cluster.** A 6- or 8-big-core CPU derives 3–4 intra-op threads;
  a single-big budget core derives 1. No device list, no constant.
- **Int8 acceleration is automatic.** ORT's MLAS kernels dispatch on HWCAP at load time: the *same* int8
  graphs run on plain NEON here (Armv8.0), and would use **SDOT/UDOT** (Armv8.2 dot-product), **i8mm**
  (Armv8.6), or **SME/SME2** matrix units on capable cores — materially faster int8 GEMMs with zero
  config from us. The detector already surfaces these flags (`dotprod`, `i8mm`, `sve2`, `sme2`) so a
  future phase can additionally pick an execution provider or specialised kernel when they are present.
- **Architecture-aware headroom.** The `archLabel` inference (v8.2/v8.6/v9 from features) is the hook for
  ISA-specific policy later; today it drives reporting and the thread rule, which is all this phase needs.

The portability claim is validated *by construction* on the one core available (Armv8.0, NEON-only):
detection is correct, the policy reproduces the measured optimum, and the acceleration path for newer
cores is the runtime's own MLAS dispatch, not something we would have to re-code per chip.

## Benchmark evidence

Adaptive policy on SM-M315F (`MtBenchmarkTest`, 30 runs/sentence; policy auto-selected `intra=2`,
`arena=off`). Parity exact — output identical to every prior phase.

| sentence | tokens | total median | p95 | stdev | decode median | tok/s | mem |
|---|---|---|---|---|---|---|---|
| Water. | 2 | 163.2 ms | 193.0 | 11.1 | 126.1 | 15.9 | — |
| Hello, how are you? | 6 | 365.1 ms | 400.3 | 16.4 | 296.9 | 20.2 | — |
| The weather… | 12 | 667.2 ms | 686.5 | 18.4 | 564.7 | 21.3 | 620 MB |

Matches the Phase 7 hand-tuned config (Weather 672.6 / p95 694.7 / stdev 20.7 / 605 MB) within noise —
the policy *derives* that optimum rather than hard-coding it. Raw evidence:
`model_pipeline/bench_arm.jsonl`.

## Result

The runtime is capability-aware: it reads the CPU and configures ORT from what it finds, with no
device-specific constant in the code. On the SM-M315F it reproduces the previously hand-tuned optimum;
on newer Arm cores it scales threads to the big cluster and inherits MLAS int8 acceleration
(dotprod/i8mm/SME) automatically. Optimisations are portable across the Arm ecosystem, validated on the
available Armv8.0 device.
