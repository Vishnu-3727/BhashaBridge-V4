# BhashaBridge V4 — Dependency Rules

Which parts of the app may reference which. Non-negotiable, and the cheapest architectural property
to keep if enforced from commit one — and among the most expensive to recover once lost.

---

## The graph

```
                    ┌──────────┐
                    │   ui/    │         Activities, ViewModel, views
                    └────┬─────┘
                ┌────────┴────────┐
                ▼                 ▼
          ┌──────────┐      ┌──────────┐
          │ speech/  │      │   mt/    │   ASR + TTS  |  translation
          └────┬─────┘      └────┬─────┘
                └────────┬────────┘
                         ▼
                  ┌─────────────┐
                  │    root     │        BhashaBridgeApp, Direction
                  └─────────────┘

    bench/  ──────► reads any subsystem. Nothing may depend on bench/.
```

Arrows point in the only permitted direction. There is no arrow back up, and no arrow between
`speech/` and `mt/`.

---

## Permission matrix

| From ↓ / May reference → | ui | speech | mt | root | bench |
|---|:--:|:--:|:--:|:--:|:--:|
| **ui** | — | ✅ | ✅ | ✅ | ❌ |
| **speech** | ❌ | — | ❌ | ✅ | ❌ |
| **mt** | ❌ | ❌ | — | ✅ | ❌ |
| **root** | ❌ | ✅¹ | ✅¹ | — | ❌ |
| **bench** | ✅ | ✅ | ✅ | ✅ | — |

¹ `BhashaBridgeApp` constructs and releases the native resources of `speech/` and `mt/` — that is its
one job as process-scoped owner (`ARCHITECTURE_RULES.md` R4.4). It calls constructors and `release()`.
It never calls `translate()` or `startListening()`. Ownership is not orchestration.

---

## The four rules

### D1 — Dependencies point downward. Always.

A lower layer never references a higher one. `mt/` must not import from `ui/`; `speech/` must not
import from `ui/`. Enforced by review and by import inspection.

**Why.** An upward reference is how a subsystem acquires a second responsibility without anyone
deciding it should. The moment `mt/` can see a `TextView`, formatting output becomes a one-line
change that lives in the wrong place forever.

### D2 — `speech/` and `mt/` never reference each other.

This is the load-bearing rule, and it is a deliberate departure from the layered sketch
`UI → Speech → Translation` in the Phase 2B brief.

Speech produces text. Translation consumes text. They never touch. `ui/` connects them:

```
SpeechInput ──emits──► ui/TranslateViewModel ──calls──► MtEngine
```

**Why not chain them.** Chaining reads naturally — speech feeds translation — but it makes `speech/`
depend on translation for a data flow it does not own. Then typed-text translation (no speech
involved) has to route around `speech/`, or `speech/` grows a passthrough path for text that never
came from a microphone. V3.4.1 had exactly one orchestrator for exactly this reason; the mistake
there was that the orchestrator was a 961-line Activity, not that orchestration existed.

Keeping them siblings means:
- Either can be tested with no knowledge of the other.
- Typed input and spoken input reach `mt/` by the same path, so they cannot diverge.
- The graph is a tree. Trees cannot cycle.

### D3 — No circular dependencies. Structurally, not by discipline.

The matrix above has no cycles by construction. Any proposed dependency that would create one is
rejected — and the correct fix is almost always that the shared thing belongs at the root, or that
the two classes are one class.

**The `Direction` case, worked.** `mt/` needs it (which model), `speech/` needs it (which Vosk model,
which TTS voice), `ui/` needs it (which labels). Putting it in `mt/` forces `speech/ → mt/` for a
two-value enum — an entire dependency edge bought to share a type with no behaviour. It lives at the
root instead, which both may depend on. This is what the root package is *for*, and it is the reason
the three-file cap exists to keep it from becoming anything else.

### D4 — `bench/` observes. Nothing observes `bench/`.

`bench/` may read any subsystem. No subsystem may import from `bench/`.

**The test:** delete `bench/` and the app still compiles and behaves identically. If it does not,
measurement has leaked into function, and every benchmark number afterwards is measuring the
instrument as much as the app (`ARCHITECTURE_RULES.md` R13.3).

Metrics are emitted through a small interface owned by the subsystem, no-op in release — never by a
subsystem calling into `bench/`.

---

## External dependencies

| Library | Only permitted in |
|---|---|
| ONNX Runtime (`ai.onnxruntime.*`) | `mt/` |
| Vosk (`org.vosk.*`) | `speech/` |
| `android.speech.tts.*` | `speech/` |
| `android.view.*`, `android.widget.*` | `ui/` |
| Coroutines | anywhere |
| AndroidX lifecycle | `ui/`, root |

**An `import ai.onnxruntime.*` outside `mt/` is a build-breaking review failure.** It means ONNX
Runtime has leaked out of the one subsystem that is allowed to know it exists — and the Phase 7
runtime sweep, which changes execution providers and thread configuration, assumes exactly one place
in the codebase constructs a session.

Same reasoning for Vosk in `speech/`.

`Context` is permitted in `mt/` and `speech/` for asset access **only** — never for UI, resources, or
`startActivity`.

---

## What this buys

**Phase 7 (Arm runtime sweep)** touches `mt/RuntimeConfig.kt` and `mt/OnnxModels.kt`. Two files. If
session construction were spread across the app the way V3.4.1 spread direction handling, sweeping an
execution provider would be a cross-cutting edit with cross-cutting risk.

**Phase 5 (KV cache)** rewrites `mt/GreedyDecoder.kt`. `ui/` and `speech/` cannot observe the change
because they cannot see the decoder — which is what makes the parity gate meaningful: if output
changes, the decoder changed it, and nothing else could have.

**Rotation-safety** is structural, not vigilant. `ui/` cannot hold an `OrtSession` because `ui/`
cannot import `ai.onnxruntime`. V3.4.1's 639 MB rotation leak was possible because `MainActivity`
could reach all the way down to a native session; here the compiler refuses.

---

## Enforcement

**Today: review.** Every PR checks imports against the matrix. Grep is sufficient and honest:

```bash
grep -rn "ai.onnxruntime" app/src/main/java --include=*.kt | grep -v "/mt/"      # must be empty
grep -rn "org.vosk"       app/src/main/java --include=*.kt | grep -v "/speech/"  # must be empty
grep -rn "android.widget" app/src/main/java --include=*.kt | grep -v "/ui/"      # must be empty
```

**Not today: a module-per-subsystem Gradle build.** Real Gradle modules would make violations
impossible to compile rather than merely rejected in review — genuinely stronger. It is not being
built now because three greps enforce the same rules at a fraction of the build-time cost, and
splitting a ~2200-line app into four Gradle modules for compiler-enforced boundaries is exactly the
enterprise-imitating complexity `ARCHITECTURE_RULES.md` R0 exists to stop.

**The trigger to revisit:** if a boundary violation ever reaches `master`, the greps have failed as
enforcement and modules become justified. Recorded here so that decision is made on evidence rather
than taste.
