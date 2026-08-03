# BhashaBridge V4 — Decoding Architecture

Phase 4. The decoding subsystem: one abstraction, two interchangeable strategies (greedy, beam
search), designed so the final choice is made by measurement, not assumption.

Status: **abstraction + both strategies implemented and unit-tested. Device benchmark and
recommendation are BLOCKED on the `mt/` runtime — see §6. No recommendation is made here, on
purpose (§7).**

---

## 1. The abstraction

Three small types in `mt/`, no more:

```
Decoder        interface: decode(logits: LogitsSource, sourceLen: Int): LongArray
LogitsSource   fun interface: nextLogits(prefix: LongArray): FloatArray
DecodeConfig   data class: start/eos token, maxSteps, minTargetLen, repetitionPenalty, noRepeatNgram
```

`GreedyDecoder` and `BeamSearchDecoder` both implement `Decoder`. That is the whole surface.

No dependency injection, no service locator, no factory hierarchy — the brief forbade them and they
would earn nothing here. Selecting a strategy is `val decoder = GreedyDecoder()` versus
`BeamSearchDecoder(beamWidth = 2)`. MtEngine will hold one `Decoder` and never mention which.

Shared per-step rules (repetition penalty, no-repeat-ngram blocking, argmax) are internal top-level
functions in `Decoder.kt`, not a base class: they are pure transforms on a `FloatArray` with no
state to inherit, and a one-method superclass is exactly the ceremony `ARCHITECTURE_RULES.md` R0
exists to stop.

---

## 2. LogitsSource is the seam that pays for itself in Phase 5

A decoder's only view of the model is `nextLogits(prefix) -> FloatArray`. It does not know ONNX
Runtime, the tokenizer, or the encoder exist. This is not abstraction for its own sake — it is the
exact boundary the KV-cache work needs:

- **Today (uncached graph):** MtEngine's `LogitsSource` runs the decoder session on the *whole*
  prefix and returns the last logits row. O(n²) over the sequence, as documented in
  `model_pipeline/MODEL_PIPELINE.md` §4.
- **After the cached export (Phase 5):** the same `LogitsSource` feeds only the last token plus
  cached key/values. O(n).

The signature does not change, so **neither `GreedyDecoder` nor `BeamSearchDecoder` changes.** The
decoders are written once, against the interface, and survive the single biggest optimisation in the
plan untouched. That is the return on keeping decoding logic and model I/O in separate types.

---

## 3. The two strategies (semantics mirror v3.4.1)

Both reproduce `Translator`'s behaviour so the eventual parity gate has a like-for-like baseline:
start/eos token `2`, length cap `max(14, sourceLen)`, repetition penalty `1.1`, no-repeat-3-gram
blocking, and — for beam — length-normalised scoring at width `2`.

**One deliberate divergence from v3.4.1: `maxSteps` is `128`, not `18`.** v3.4.1 paired a length cap
of `max(14, sourceLen)` with a decode loop bounded at 18 steps, so the cap was fiction above 18
tokens and longer inputs were silently truncated mid-sentence. `maxSteps` is now an absolute runaway
ceiling rather than the working limit, and `DecodeConfig.targetCap` clamps into it so a single number
bounds generation. Outputs at 2/6/12 tokens — every benchmark sentence and every parity golden in the
project — are unaffected; only sources over 18 tokens change, and they change from truncated to
complete. The ceiling stays finite because `Tokenizer.encode` applies no source-length limit; see the
KDoc on `DecodeConfig.maxSteps` for the latency arithmetic.

- **Greedy:** one `nextLogits` call per token; argmax after the penalty/blocking rules; stop on eos
  or the cap. This is what v3.4.1 shipped for both directions.
- **Beam:** keeps the best `beamWidth` partial sequences, expands each by its top-`beamWidth`
  tokens, ranks all expansions by length-normalised log-probability, retires finished beams, keeps
  the best active ones. Returns the best completed beam. v3.4.1 implemented this identically but
  **never called it** — it was dead code; here it is a real, selectable, tested strategy.

**Cost, stated but not yet measured:** on the uncached graph each active beam is a full decoder
forward pass every step, so width-K beam does ~K× greedy's decoder work per step, and beams run at
least as long. The latency cost is real; whether the quality gain justifies it on this device is §7's
open question.

---

## 4. Correctness — what is proven now

`DecoderTest` (7 tests, JVM, no Android, no model — `app/src/test/.../mt/DecoderTest.kt`), all green:

| Test | Proves |
|---|---|
| greedy takes locally-best token, stops at eos | argmax selection + eos stop |
| length cap stops without emitting eos | the `max(minTargetLen, sourceLen)` halt |
| beam looks ahead and beats greedy | beam keeps a lower-immediate/higher-total path greedy discards |
| beam width 1 is greedy | width-1 beam ≡ greedy (invariant) |
| repetition penalty damps each distinct prior token once | penalty sign handling + dedup |
| no-repeat-ngram blocks the recreating token | n-gram suffix scan |
| argmax returns lowest index on ties | tie-break matches v3.4.1's strict `>` |

**What these do NOT prove:** parity with v3.4.1's actual translations. That needs the real decoder
graph and the tokenizer, and is an on-device check in Phase 5/6. These tests verify the *algorithms*
against a synthetic `LogitsSource`; they are decisive for the decode logic and silent on the model.

---

## 5. Where benchmark instrumentation goes

Not inside the decoders — they stay pure and JVM-testable, and `android.util.Log` (which `Metrics`
uses) is not available under plain JUnit. Instrumentation lives at the MtEngine boundary, which owns
the run and can read the token count off the returned `LongArray`:

```
Metrics.begin("translate")
Metrics.stage("tokenize"); … ; Metrics.stage("encode"); …
Metrics.stage("decode");   val ids = decoder.decode(source, srcLen)
Metrics.counter("tokens", (ids.size - 1).toLong())
Metrics.counter("decode_steps", stepsRun.toLong())
Metrics.end()
```

`decode_steps` is what separates greedy from beam in the data: beam's step count and per-step cost
both rise. This is a call-site plan, not code — MtEngine does not exist yet (§6).

---

## 6. The blocker: the device benchmark needs the `mt/` runtime

Implementation-order steps 3 (V3 parity), 6 (Metrics integration), and 7 (device benchmark), plus
every latency/memory/throughput deliverable, require running real translation on the SM-M315F. That
requires machinery Phase 4 does not build and Phase 3 explicitly forbade:

- **Tokenizer** — SentencePiece over `model.SRC`/`model.TGT` (and HI→EN variants).
- **ONNX sessions** — encoder + decoder `OrtSession` construction in `mt/` (the `LogitsSource` impl).
- **MtEngine** — ties tokenizer + sessions + a `Decoder`, owns `Metrics` instrumentation.
- **~278 MB of int8 assets** wired into the app.

That is Phases 5–6 of `ENGINEERING_PLAN.md`, not "one simple decoder abstraction." **No device
numbers are reported, and none are invented.** Fabricated benchmarks would violate the phase's own
RULE ("do not use a single run as evidence") more deeply than reporting none.

---

## 7. Recommendation: WITHHELD, pending evidence

The phase rule is explicit: the recommendation must be evidence-based, beam search must not be
assumed superior, and greedy is a valid outcome. **There is no device evidence yet, so there is no
recommendation.** Presenting one now would be the exact failure the rule guards against.

What is decided is the *procedure*, so the recommendation is mechanical once §6's runtime exists:

**Benchmark method** — on the SM-M315F, both strategies, identical inputs (a fixed sentence set
spanning short/medium/long), warm-up run discarded, **n ≥ 30** timed iterations each. Report per
metric: **n, median, p95, standard deviation** — never a single run.

**Metrics:** total translation latency · decoder latency · tokens/sec · peak memory (`meminfo`) ·
output token count · CPU utilisation if practical.

**Decision rule (set before the data, to keep it honest):**
- **A — keep beam** only if it improves translation quality measurably *and* median total latency
  stays within the app's interaction budget (target < 900 ms, `ENGINEERING_PLAN.md`).
- **B — revert to greedy** if beam's latency blows the budget without a quality win the user would
  notice — the likely outcome given §3's K× cost on an uncached O(n²) graph, but that is a
  hypothesis to test, not a conclusion.
- **C — keep both, runtime-selectable** only if quality and latency genuinely trade off per input
  length and there is a real use case for choosing per translation. Default to **not** C: two live
  strategies is complexity that must earn its place.

The abstraction supports all three at zero extra cost, which is the point — the architecture does not
prejudge the measurement.
