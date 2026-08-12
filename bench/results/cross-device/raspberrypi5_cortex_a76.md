# Raspberry Pi 5 (Cortex-A76) — EN→HI, 2026-08-12

**Off-Android data point.** Run on a Raspberry Pi 5 Model B Rev 1.1 (8 GB) because its ISA fills the
one hole in this database: **`asimddp` (dotprod), no `i8mm`, no SVE, no SME**. Every other entry is
either below that line (SM-M315F: NEON only) or above it (SM-S948B: SME). The KleidiAI direction has
been contested between exactly those two (§3.20 vs §3.38–§3.42), and no measured part sat in between.

| | |
|---|---|
| device | Raspberry Pi 5 Model B Rev 1.1, 8 GB |
| CPU | 4 × Cortex-A76 @ 2.4 GHz, **all performance cores** (no big.LITTLE split) |
| features | `fphp asimdhp asimddp` — **dotprod yes, i8mm no, sve no, sme no** |
| OS | Raspberry Pi OS Bookworm (Debian 12), aarch64, kernel via Pi 5 firmware |
| runtime | **onnxruntime 1.28.0** (Python), against Android's 1.27.0 — see caveats |
| thermals | 46.1 → 55.4 °C across the sweep, `get_throttled=0x0` throughout (never throttled) |

## What was run

`bb-pi/bb_pi.py`, a faithful port of the Android decode contract — `Tokenizer.encode/decode`,
`GreedyDecoder` (start/EOS = 2, `maxSteps` 128, `targetCap = max(14, 1.6×src + 8)`, repetition penalty
1.1, no-repeat-3gram), and `CachedLogitsSource`'s init/step KV-cache flow with `past_key_values.*` fed
from the previous `present.*`. The same INT8 graphs and `weights.bin` the APK ships.

**Parity is exact and was checked before every arm:** `Water.` → `पानी ।`, and
`The weather is very nice today and I want to go outside.` →
`आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ ।` at **12 tokens**, identical to the phone.

5 arms × 3 rotated rounds, n=12 per arm per round, 3 warm-up translations discarded. The host's
`defect-inspect` service was stopped for the duration (it competes for the cores) and restarted after.

## Results — medians of the three rounds

| arm | long (12 tok) | short (2 tok) | tok/s (long) |
|---|---|---|---|
| `intra=1`, KleidiAI on | 224.2 ms | **56.2 ms** | 53.5 |
| **`intra=2`, KleidiAI on** (policy derives this) | **223.0 ms** | 57.1 ms | **53.8** |
| `intra=2`, KleidiAI off | 223.6 ms | 57.3 ms | 53.7 |
| `intra=4`, KleidiAI on | 304.9 ms | 110.1 ms | 39.4 |
| `intra=4`, KleidiAI off | 308.3 ms | 110.8 ms | 38.9 |

Per-round spread inside the `intra=1`/`intra=2` arms is remarkable — **stdev 0.1–0.5 ms** on n=12, the
tightest of any device in this database. A 4-core part with no efficiency cluster and no scheduler
migrations is simply a quieter instrument than a phone.

## Three findings

1. **The `[1,2]` intra-op clamp holds here, and the margin is the largest yet measured.** `intra=4` is
   **+36.7% on the long sentence and +93% on the short one**, with 20–80× the stdev. This part derives
   `perfCores / 2 = 2` from the shipping heuristic, which is the arm that wins. Note *why* it is a
   stronger result than the M31's: the Pi 5 has **four equal performance cores and no LITTLE cluster**,
   so `intra=4` cannot be blamed on migration onto slow cores — the collapse is intra-op coordination
   overhead on a workload that is a sequence of small GEMMs, exactly the mechanism §3.8 proposed.
2. **KleidiAI does nothing on dotprod-only silicon — a clean null.** 223.0 vs 223.6 ms at `intra=2`
   (**0.27%**, against a within-arm stdev of 0.1–0.5 ms) and 304.9 vs 308.3 ms at `intra=4` (1.1%).
   This is the predicted result rather than a surprise: `ExecutionPolicy`'s KDoc records that
   KleidiAI's NEON `dotprod`/`i8mm` kernels are `qsi4c32p` — **4-bit, therefore inert for this
   project's 8-bit weights** — so only its SME `qsi8cxp` kernels can ever run. A part with dotprod and
   no SME should show nothing, and shows nothing. **It supports the shipping predicate
   `disableKleidiAi = caps.sme`** as the right shape: the flag matters only where SME exists.
3. **`intra=1` ties `intra=2`.** 224.2 vs 223.0 long (0.5%), and `intra=1` is actually *ahead* on the
   short sentence (56.2 vs 57.1, −1.6%) at half the CPU. Same shape as the S26 Ultra's §3.39 finding,
   weaker in magnitude. It does not change the policy — the tie is inside the run's own repeatability —
   but it is the second wide-core part where the second thread earns nothing.

## Against the phone

Same sentences, same graphs, same decode rules, both medians:

| | SM-M315F (Exynos 9611) | Pi 5 (Cortex-A76) | ratio |
|---|---|---|---|
| long, 12 tokens | 627 ms | **223 ms** | **2.81× faster** |
| short, 2 tokens | 158 ms | **56 ms** | **2.82× faster** |

The ratio being identical on both sentence lengths says the gap is raw per-token compute, not a fixed
overhead the Pi avoids.

## Caveats — what this run does NOT establish

- **ONNX Runtime versions differ** (1.28.0 here, 1.27.0 on Android). The KleidiAI null is therefore
  suggestive for the Android build rather than conclusive for it; MLAS dispatch could differ between
  those releases.
- **Load time is not comparable.** This harness loads the raw `.onnx` with `ALL_OPT` on every run
  (2,820 ms for all three graphs); the app loads a pre-baked artifact `NO_OPT`. Nothing here bears on
  §3.44–§3.47.
- **Not the shipping runtime.** Python bindings, no Android thread affinity (`intra_op_thread_affinities`
  was not set — the Pi has no cluster to pin to), no `optCache`, no vocabulary cache.
- One device, one session. The thermal envelope was benign throughout (never throttled), which is *not*
  the phone's situation and is part of why the variance is so low.

## Reproducing

```bash
scp app/src/main/assets/{encoder_int8.onnx,decoder_init_int8.onnx,decoder_step_int8.onnx,\
dict.SRC.json,dict.TGT.json,weights.bin} pi:~/bb-pi/models/
ssh pi 'systemctl --user stop defect-inspect'     # it competes for the cores
ssh pi 'cd ~/bb-pi && python bb_pi.py --intra 2 --runs 12 [--no-kleidi]'
ssh pi 'systemctl --user start defect-inspect'
```

`bb_pi.py` is kept with this entry as `bench/results/cross-device/bb_pi.py`.
