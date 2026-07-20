# BhashaBridge V4 — Coding Standards

Mechanical rules. Where `ARCHITECTURE_RULES.md` states a principle, this document gives the number
that makes violating it visible.

---

## 0. The ladder

Before writing a new implementation, stop at the first rung that holds:

1. Does this need to exist at all?
2. Does it already exist in this codebase?
3. Does the Kotlin/Java stdlib do it?
4. Does an Android platform feature do it?
5. Does an already-present dependency do it?
6. Can it be one line?
7. Only then: the minimum code that works.

The ladder runs **after** understanding the problem, never instead of it. A small diff in the wrong
place is not laziness, it is a second bug.

---

## 1. Size limits

| Unit | Limit | Enforcement |
|---|---|---|
| File | **400 lines** | hard — review rejects |
| Function | **60 lines** | hard |
| Function parameters | **5** | soft — 6+ needs a reason in review |
| Nesting depth | **4** | hard |
| Line length | **120 chars** | hard |
| Root package | **3 files** | hard (`PROJECT_STRUCTURE.md`) |

Limits count real code — KDoc and blank lines excluded.

**Why a hard number.** V3.4.1's `MainActivity` never had a commit where it "became too large". It
crossed 300, then 600, then 961, and each individual diff was small and defensible. A number trips
before anyone's judgement has to.

**Hitting a limit is a question, not an instruction.** Ask: is there a second responsibility here
(split it), dead code (delete it), or genuinely one long cohesive thing (see §8)?

---

## 2. Documentation format

Every class, exactly these four headings, in this order, nothing else:

```kotlin
/**
 * Purpose:  Decodes token ids to text using the target vocabulary.
 * Owns:     Nothing. Borrows the vocabulary map from MtEngine.
 * Lifetime: Process
 * Thread:   MT dispatcher only — reuses a shared StringBuilder.
 */
```

- **Purpose** — one sentence, no "and". Needing "and" means R3.1 is violated.
- **Owns** — what it creates and destroys, or the literal word `Nothing`. Never blank.
- **Lifetime** — `Process` | `Screen` | `View`.
- **Thread** — which dispatcher, and whether confinement is load-bearing.

No "Performance notes", no "Usage", no "How it works" sections. V3.4.1's headers grew to six sections
narrating the code directly beneath them; that prose was accurate when written and went stale anyway,
because a comment describing *what* code does competes with the code and loses.

Public functions get KDoc only when the signature is not self-explanatory. `fun release()` does not
need a comment saying it releases.

---

## 3. Comment format

**Explain why. Never what.**

```kotlin
// BAD  — restates the code
// loop through tokens and add them
// GOOD — explains a non-obvious decision
// Single lock: two concurrent translations would corrupt the shared decode buffers.
```

**Constants carry unit and rationale:**
```kotlin
// ms — Vosk emits partials every ~100ms; 250 keeps the translator from flooding.
private const val STREAM_THROTTLE_MS = 250L
```

**Deliberate simplifications are marked**, naming the shortcut and its upgrade path:
```kotlin
// ponytail: linear scan over 64k logits. Fine at one argmax per step;
// revisit if top-k sampling is ever added.
```
These are harvestable into a debt ledger. An unmarked shortcut is indistinguishable from a bug.

**Banned:** commented-out code (git remembers), `// TODO` without an owner and a phase number,
decorative separator banners, and comments restating a rule from these documents.

---

## 4. Naming

| Thing | Convention | Example |
|---|---|---|
| Class | `PascalCase`, a noun that says what it **is** | `GreedyDecoder` |
| Function | `camelCase`, verb first | `decodeStep()` |
| Boolean | `is` / `has` / `can` prefix | `isRecording` |
| Constant | `SCREAMING_SNAKE`, unit in the name | `STREAM_THROTTLE_MS` |
| Dispatcher | `<subsystem>Dispatcher` | `mtDispatcher` |
| Backing field | no `_` prefix — use a private val with a different name | |

**Banned class suffixes:** `Manager`, `Helper`, `Util`, `Service`, `Handler`, `Processor`,
`Controller`, `Impl`, `Base`, `Abstract`, `Holder`, `Wrapper`.

`OnnxSessionManager` did not manage sessions — it created two and copied two files. The `Manager`
suffix made "and it also copies assets" feel natural, because a Manager manages whatever it is
handed. Precise names resist scope creep. Vague names invite it.

**Abbreviations:** only `MT`, `ASR`, `TTS`, `KV`, `EP`, `PCM`, `RMS` — all standard in this domain.
Never invent new ones. `cfg`, `impl`, `req`, `res`, `mgr` are banned: they save nothing and cost
clarity.

---

## 5. Nullability

**R5.1** — `!!` is banned. No exceptions. V3.4.1's `_encoderSession!!` was the visible symptom of the
ownership defect in L2: the `!!` existed because nobody could say when the field was valid.

**R5.2** — `lateinit` is permitted **only** for Android-injected view references initialised in
`onCreate`. Nowhere else.

**R5.3** — Prefer making null impossible over handling it. If a field is always set by the time it is
used, it should be a constructor `val`.

**R5.4** — A nullable type must mean something. `Model?` meaning "not loaded yet" is fine. `String?`
meaning "empty or missing or failed" is three states in one type — use a sealed class.

**R5.5** — Public functions never return `null` to signal failure. See §6.

---

## 6. Exception handling

**R6.1** — Failure is never encoded in a success-typed value. Return a sealed result:

```kotlin
sealed interface TranslationResult {
    data class Success(val text: String, val confidence: Float) : TranslationResult
    data class Failure(val reason: FailureReason) : TranslationResult
}
```

V3.4.1 returned the string `"Translation failed"` from a function typed `String`. It was rendered in
the output field in the same colour as a real translation, spoken aloud, and stored in history.

**R6.2** — Catch narrowly. `catch (e: OrtException)` — not `catch (e: Exception)` around 40 lines,
which converts every bug inside, including NPEs, into one user-facing message and discards the stack
trace that would identify it.

**R6.3** — Never catch `Throwable`, `Error`, or `OutOfMemoryError`.

**R6.4** — A swallowed exception logs at `w` **with the reason it is safe to swallow**:
```kotlin
catch (e: OrtException) {
    // Warm-up is an optimisation; skipping it costs first-call latency, not correctness.
    Log.w(TAG, "Warm-up skipped: ${e.message}")
}
```
Silent `catch` blocks are banned.

**R6.5** — Never catch `CancellationException`. It breaks structured concurrency.

**R6.6** — Validate at trust boundaries — imported files, ASR output, user text. **This rule is
exempt from the ladder**; never simplify away validation, error handling that prevents data loss, or
security checks.

---

## 7. Logging

Tags: `BB.MT`, `BB.Speech`, `BB.UI`, `BB.Bench`. One per subsystem, so `adb logcat -s BB.*` shows
the app and nothing else.

| Level | Meaning |
|---|---|
| `e` | broken, user affected |
| `w` | degraded, recovered — must state why recovery is safe |
| `d` | debug only, `BuildConfig.DEBUG`-gated |

`i` and `v` are unused. Two fewer judgement calls.

**Never log user content in release builds.** V3.4.1 logged `"[$direction] $text → $result"` at
`Log.d` on every translation — the user's spoken words, in an app whose emergency-phrase feature
implies medical and safety contexts. All content logging is `BuildConfig.DEBUG`-gated.

**Never log inside the decode loop.** It runs up to 18× per translation; string formatting there is
exactly the allocation §9 exists to prevent. Measurements go through `bench/`.

---

## 8. When to split a class — and when not to

**Split when:**
- Purpose needs "and" (R3.1).
- Two halves change for different reasons.
- One half is tested and the other cannot be.
- One half touches native resources and the other touches views.

**Do NOT split when:**
- The only reason is the 400-line limit. If it is genuinely one cohesive responsibility, the limit is
  asking a question and the answer can be "no, it's fine" — recorded in review.
- The pieces would share mutable state. Two classes passing one mutable buffer back and forth is one
  responsibility with a seam through it, which is worse than one honest class.
- It would create an interface with exactly one implementation.
- It would produce a class whose only job is delegating to another class.

**The failure mode to avoid.** V3.4.1's error was one class doing everything. The opposite error —
twelve classes each doing a twelfth of one thing — is not an improvement; it is the same complexity
with more indirection and more files. `AudioCaptureController` and `VoskModelLoader` were already
this: two classes that could not function without each other, constructed with references to each
other's executors. V4 merges them into `SpeechInput`.

---

## 9. Performance

**§9.1 — Optimise only what is measured.** A performance change without a before/after number is not
accepted, however obviously correct it looks.

**§9.2 — The decode loop is the one hot path** and is held to a stricter standard than everything
else: **zero allocation per step.**

Banned inside the loop:
- `copyOf()`, `toList()`, `toTypedArray()`
- `MutableList<Long>` and any boxed-primitive collection
- per-step `Set` or `Map` construction
- string templates or `format()`
- lambdas capturing loop variables

Required:
- all buffers preallocated at engine construction
- `LongArray` / `FloatArray`, never `List<Long>` / `List<Float>`
- `OnnxTensor.getFloatBuffer()`, **never** `OnnxTensor.value`

`value` materialises the entire `[1, seq, vocab]` output as boxed nested Java arrays — ~4.6 MB per
step at seq=18, vocab≈64k — in order to read 64k floats. `Translator.kt:204` is the single line
dominating the measured ~50 MB transient allocation per translation.

**§9.3 — Everywhere else, clarity wins.** Allocating in a click listener is fine. §9.2 is a scalpel,
not a lifestyle; applying it project-wide buys unmeasurable gains at the cost of unreadable code.

**§9.4 — Hardware tunables stay exposed.** Thread counts, affinity masks, buffer sizes, timeouts:
named constants or `RuntimeConfig` fields, never buried in an expression. Real silicon does not match
the datasheet, and a value that cannot be swept cannot be tuned.

---

## 10. Formatting

- Kotlin official style, 4-space indent, no tabs.
- No wildcard imports. V3.4.1 used `import android.os.*` and `import ai.onnxruntime.*`, which hides
  what a file actually depends on.
- No fully-qualified names inline when an import will do. V3.4.1's `MainActivity` wrote
  `android.view.View.VISIBLE` and `android.graphics.Color.parseColor` dozens of times.
- Trailing commas in multi-line argument lists.
- One top-level class per file; the filename matches the class. V3.4.1 hid `EnPostProcessor` at the
  bottom of `Translator.kt` and `TranslationDirection` at the top.
- Expression bodies only when the expression fits on one line.

---

## 11. Testing

Not a test-coverage mandate. The rule is: **non-trivial logic leaves one runnable check behind.**

| Code | Check required |
|---|---|
| Tokenizer encode/decode | yes — round-trip assertions |
| Decode loop control flow | yes — EOS, max-length, n-gram blocking |
| Result/failure types | yes |
| Audio resampling | yes — known input, known output |
| View binding, listeners | no |
| One-line delegation | no |

The smallest thing that fails if the logic breaks. No frameworks beyond JUnit, no fixtures, no mocks
unless there is nothing else. YAGNI applies to tests too — but a decode loop with no test is not lazy,
it is unfinished.

The **parity harness** (`ENGINEERING_PLAN.md` §3.2) is the highest-value test in the project: 200
sentences through v3.4.1 and V4, compared. It is worth more than any unit test here, because it is
the only thing standing between "3× faster" and "3× faster and subtly wrong".
