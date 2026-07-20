# Lessons from V3.4.1

Permanent record. Every lesson below is a real defect located in the v3.4.1 source, with the rule it
produced in V4.

**How to read this.** Each *Cause* section is written without blame, because none of these were
careless. Every one of them was a locally reasonable decision that compounded. That is the actual
lesson: architecture does not fail through bad commits, it fails through good commits that nobody
was measuring against a rule.

`ENGINEERING_PLAN.md` §1.5 and §1.6 hold the full defect list including the pure-performance ones.
This document keeps only the defects that changed how V4 is *organised*.

---

## L1 — MainActivity became the whole application

**Problem.** 961 lines. It owned both `Translator` instances, both loading states, the direction
state, the streaming-partial gate, the debounced typed-input corrector, three executors, the drawer,
the history dialog, the language dialog, audio-file import, and the bilingual UI. `runTranslation()`
alone — 70 lines — chose the translator, applied the discard policy, timed the call, mutated six
views, spoke the result, and recorded history.

**Cause.** No rule said where new behaviour goes, and `MainActivity` already had references to
everything. Each feature was cheapest to add there. Adding a feature to the class that already holds
all the references is always the shortest diff *today*; the cost is paid by everyone who reads it
afterwards. There was never a commit where the file "became too large" — it passed 300 lines without
anyone noticing, then 600, then 961.

**Rule adopted.** R3.3: the Activity renders, the ViewModel decides, and they are separate files.
`CODING_STANDARDS.md` §1: 400-line hard cap, enforced at review — a number that trips before anyone's
judgement has to.

---

## L2 — Native resources had no owner

**Problem.** `OnnxSessionManager.release()` (line 113) closes both `OrtSession`s. It is correct code.
It has **zero call sites**. `MainActivity.onDestroy()` released audio capture, Vosk, TTS and three
executors — then dropped both `Translator` references without releasing them. Every rotation leaked
~639 MB of native heap and re-paid the 8.6-second model load.

**Cause.** The chain `Activity → Translator → OnnxSessionManager → OrtSession` had a creator at every
level and a destroyer at none. Each class assumed the layer above handled teardown. The cleanup
method existed, which made the code *look* safe in review — a `release()` in the file reads as
evidence that release happens.

**Rule adopted.** R4.1: `Creator == Holder == Destroyer`, or there is no owner. R4.4: native
resources are owned by the process, never an Activity. R4.5: **a `release()` method must have a call
site in the same commit that introduces it** — this is the rule that would have caught it, because
the defect was never a missing method.

---

## L3 — Translator did everything except own its own memory

**Problem.** 494 lines: tokenizer construction, encoder invocation, two full decode strategies,
repetition penalty, n-gram blocking, confidence scoring, and a bundled `EnPostProcessor` object doing
six regex passes on output.

**Cause.** It started as "the class that translates" — a name broad enough to justify anything
translation-adjacent. Every addition was defensible against that name. A class named for an entire
domain has no admission criteria.

**Rule adopted.** R3.1: one responsibility, stated in one sentence with no "and". `mt/` splits into
`MtEngine` (entry), `GreedyDecoder` (loop), `Tokenizer`, `OnnxModels` (sessions) — four names each
narrow enough to reject work that does not belong.

---

## L4 — Dead code accumulated and looked alive

**Problem.** ~600 of 3640 lines were dead or inert:
- `SpeechManager.kt` — 302 lines, zero references anywhere.
- `translateBeam()` + `topKIndices()` — 133 lines, zero callers, fully implemented, never validated.
- Two `SpannableString` blocks in `applyUiLanguage()` — built on every call, assigned to nothing,
  targeting an `ImageView` that had replaced the `TextView` they were written for.
- `applyTricolourTitle()` — zero call sites.
- `MainActivity_backup.kt` — 78 lines at the repo root, outside the source set.

**Cause.** Deletion felt riskier than retention. The beam-search comment says it plainly: *"Retained
here for potential future use."* Nobody could prove it would never be needed, so it stayed — and then
had to be read, understood, and reasoned about by every subsequent maintainer, forever, at no benefit.

**Rule adopted.** R0: what breaks today if this does not exist? R9.5: no commented-out code. Beam
search is not being ported; git holds it, and it returns only if Phase 8 quality data demands it.

---

## L5 — A utility file became the place for homeless code

**Problem.** `FileUtils.kt` held recursive asset copying. `OnnxSessionManager` held a near-duplicate
single-file copy — the same logic, two implementations, neither aware of the other.

**Cause.** Asset copying belonged to no subsystem, and `FileUtils` accepted anything file-shaped.
A folder or file named for a *shape* rather than a *responsibility* has no criteria for rejection,
so it becomes the default destination — and duplicates form, because nobody looks inside a junk
drawer before adding to it.

**Rule adopted.** R1.2: `utils/`, `helpers/`, `common/`, `core/` and their kin are permanently
banned as folder names. R10.1: `Util`/`Helper`/`Manager` suffixes banned on classes. Code with no
subsystem is a signal that a subsystem is missing or that the code is unnecessary — never a signal
that a junk drawer is needed.

---

## L6 — Benchmarking arrived last

**Problem.** Every optimisation decision before the `bench/` harness existed was made on intuition.
`OnnxSessionManager` fixes 4 intra-op and 2 inter-op threads with a comment claiming they "were
evidently tuned against real device measurements" — hedged language that means the author was
inferring, not reporting. The real measurements, once taken, exposed a 9× latency curve nobody had
sized.

**Cause.** Measurement was treated as reporting — something you do to *present* work — rather than as
input that decides which work to do. So it was scheduled after the work it should have directed.

**Rule adopted.** R13.1: instrumentation lands before the optimisation it measures. R13.2: a claim
needs device, n, median, p95, σ, before → after. `bench/` is a first-class folder in V4, present from
Phase 2, not a phase-8 deliverable.

---

## L7 — Documentation described the code instead of governing it

**Problem.** Eleven root-level markdown reports: `Architecture_Reverse_Engineering_Report.md`,
`Engineering_Audit_Report.md`, `INDEPENDENT_AUDIT.md`, `SRP_ANALYSIS_MIGRATION_PLAN.md`,
`NAMING_AND_READABILITY_MIGRATION_PLAN.md`, and more. Class KDoc had grown to six-section headers
narrating the implementation directly below them.

**Cause.** Documentation was written *about* the code after the fact. Descriptive documentation has
no enforcement mechanism — it cannot be violated, only outdated. Two `MIGRATION_PLAN` files describing
migrations that were never completed is the clearest symptom: the documents recorded intent that
nothing held anyone to.

**Rule adopted.** R9.1: KDoc is four fixed lines — Purpose, Owns, Lifetime, Thread — all four of
which state *contracts*, not behaviour. `docs/` holds rules and decisions only. A document that
describes rather than governs does not get written.

---

## L8 — Concurrency was hand-rolled and quietly racy

**Problem.** Three `SingleThreadExecutor`s plus a `Handler`. `loadHiEnTranslatorAndVosk()`
reimplemented "wait for two parallel jobs" with two `AtomicBoolean`s and a shared closure that both
jobs call, relying on whichever finishes second to trigger completion. `hiEnLoading` — the guard
preventing a duplicate 200 MB model load — was a plain `Boolean` written from the translation thread
and read from the main thread. Not volatile, not atomic.

**Cause.** Executors were adopted early and each new async need was solved with the primitives
already present. The rendezvous was written by hand because adding a concurrency library felt
heavier than twenty lines of `AtomicBoolean` — and it was, until it needed to be read.

**Rule adopted.** R6.1: coroutines, structured concurrency. R6.2: one dispatcher per subsystem,
declared once. R6.4: shared mutable state crossing threads is volatile, atomic, or confined — no
exceptions.

---

## L9 — Failure was returned as a successful-looking value

**Problem.** `Translator.translate()` returns `String`. On any exception it returns the literal
`"Translation failed"`. `MainActivity` renders that in the output field in the same colour as a real
translation, speaks it aloud through TTS, and stores it in history as a translation.

**Cause.** The signature `translate(String): String` had no room to express failure, and adding a
result type felt like ceremony for an edge case. So the edge case was encoded in the success channel,
where the type system could no longer distinguish it.

**Rule adopted.** R12.1: failure is never encoded in a success-typed value. `mt/` returns a sealed
result. R12.3: subsystems return typed failures, never display text — a subsystem that knows about
`values-hi/` has the wrong responsibility.

---

## L10 — The bilingual system existed and governed nothing

**Problem.** `res/values-hi/strings.xml` shipped in the APK. The app never used it. Every Hindi
string was an inline `if (isHindi) "…" else "…"` pair — dozens of them across `applyUiLanguage()`,
`updateLangUI()`, and every listener in a 961-line file.

**Cause.** The first bilingual string was a one-line conditional, and it worked. Each subsequent one
matched the established pattern. By the time the pattern was clearly wrong, changing it meant
touching the whole file — so it never got changed, and the resource system sat inert.

**Rule adopted.** `PROJECT_STRUCTURE.md`, `ui/` — user-visible strings as Kotlin literals are banned.
Enforced from the first UI commit, because this is a defect that is trivial to prevent and expensive
to reverse.

---

## L11 — Two features quietly used the wrong model

**Problem.** `processAudioFile()` always transcribes with the **English** Vosk model, even when the
app is in HI→EN mode. A user importing Hindi audio gets English recognition of Hindi speech. The
KDoc documents this — *"there is no branch here to use the Hindi model"* — without flagging it as a
defect.

**Cause.** Audio-file import was built when only EN→HI existed. When HI→EN was added, the code path
was not revisited, because nothing connected "new direction" to "every place direction is consumed".
It was then documented as behaviour rather than recognised as a bug.

**Rule adopted.** `Direction` is a root-package type deliberately shared by `mt/`, `speech/`, and
`ui/`, so every direction-sensitive path is reachable from one symbol. R9.2: comments explain *why* —
a comment describing surprising behaviour without justifying it is a bug report filed in the wrong
place.

---

## L12 — The real bottleneck was outside the app, and the app was optimised anyway

**Problem.** Latency scaled 282 ms → 2518 ms across 1 → 21 words. The cause is that the decoder
recomputes attention over the entire prefix at every step. That is fixed in
`model_pipeline/export_decoder_dynamic.py`, where `DecoderWrapper.forward()` takes
`(input_ids, encoder_hidden_states, encoder_attention_mask)` — no `past_key_values` in, no `present`
out. **The exported graph physically cannot cache.**

Meanwhile the app-side tuning — thread counts, warm-up, a 256 KB copy buffer — addressed real but
much smaller costs.

**Cause.** The ONNX export was treated as a build artifact rather than as source. It lived in a
folder next to a committed virtualenv, was written once, and was never revisited. Nobody
re-read it while investigating decode latency, because it did not look like part of the app.

**Rule adopted.** `model_pipeline/` is a first-class top-level directory
(`PROJECT_STRUCTURE.md`), and Phase 3 — the KV-cache re-export — is scheduled **before** the Kotlin
decode work that depends on it. The model graph is source code. It is where the biggest wins live,
and it must be read before optimising anything downstream of it.

---

## L13 — Architecture drifted because nothing held it in place

**Problem.** Six architecture and audit documents describe a structure the code does not have. Two
migration plans describe migrations never performed.

**Cause.** The architecture was described but never made enforceable. There was no size limit that
would trip, no ownership declaration to contradict, no folder scope to violate. Every individual
commit was defensible; nothing measured their sum. This is the meta-lesson under all twelve above.

**Rule adopted.** `ARCHITECTURE_RULES.md` is written as enforceable constraints — numbers, banned
lists, required-heading formats — not as descriptions. R15 makes amendment legitimate and explicit,
because a rule that cannot be changed gets silently routed around instead, and then you are back to
six documents describing a codebase that no longer resembles them.

---

## Summary

| # | Lesson | Primary rule |
|---|---|---|
| L1 | God Activity | R3.3, 400-line cap |
| L2 | Unowned native resources | R4.1, R4.4, **R4.5** |
| L3 | Domain-named class absorbed a domain | R3.1 |
| L4 | Dead code retained "just in case" | R0, R9.5 |
| L5 | Utility file as junk drawer | R1.2, R10.1 |
| L6 | Measurement scheduled last | R13.1 |
| L7 | Descriptive, not governing, docs | R9.1 |
| L8 | Hand-rolled racy concurrency | R6.1, R6.4 |
| L9 | Failure in a success value | R12.1 |
| L10 | Unused i18n system | `ui/` string rule |
| L11 | Direction not propagated | shared `Direction` type |
| L12 | Bottleneck outside the app | `model_pipeline/`, Phase 3 first |
| L13 | Unenforceable architecture | this rule set |

Nothing here was incompetence. V3.4.1 works, ships, and produced a real measured baseline. It
accumulated these defects the way every codebase does: one reasonable decision at a time, with
nothing checking the total. The rules exist to be the thing that checks the total.
