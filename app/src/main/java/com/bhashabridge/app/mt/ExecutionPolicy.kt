package com.bhashabridge.app.mt

import com.bhashabridge.app.LogTag
import com.bhashabridge.app.logDebug

/**
 * Turns detected [CpuCapabilities] into an [OrtTuning] — the capability-aware replacement for Phase 7's
 * device-specific `OrtTuning.production()` constant. The rules are derived from the CPU, not hard-coded
 * for the SM-M315F, so the same binary configures itself sensibly on any Arm core.
 *
 * Detection runs once and is cached ([capabilities] / [current]); the sessions are built once at
 * process scope anyway, so there is no repeated cost.
 */
object ExecutionPolicy {

    /**
     * Derive session options from [caps].
     *
     * - **Threads:** intra-op parallelism = **half the performance cluster**, bounded to [1, 2]. The
     *   work is a sequence of *small* int8 GEMMs (one token at a time), which is latency-bound and
     *   saturates its intra-op parallelism almost immediately. Phase 7 measured this on the 4-big-core
     *   SM-M315F: 2 threads was the sweet spot; 4 added big.LITTLE scheduler sync/migration jitter for
     *   no median gain, and 8 (all cores, onto the efficiency cluster) collapsed.
     *
     *   The upper bound used to be 4, on the reasoning that an 8-big flagship should scale to 4. Entry
     *   #9 measured exactly that case and it does not: on the SM-S948B (8 uniform Oryon cores, so
     *   `perfCores = 8` → 4 threads) the production-path A/B — `optCache` on, NO_OPT, mmap `.ort`, the
     *   real shipping load path, 7 configs × 3 rotated rounds, n=45 each, every arm parity-exact —
     *   put `intra2` at 99 ms against `intra4`'s 104 ms on the long sentence (−4.8%) and 27 ms against
     *   31 ms on the short one (−12.9%), with `intra6`/`intra8` degrading steeply beyond that. See
     *   bench/results/cross-device/S26U_EXPERIMENTS.md §2b.
     *
     *   So the cap is 2: across nine devices spanning Armv8.0 → ARMv9 and four vendors, **no entry has
     *   ever measured 4 threads as the optimum**, and the only device whose topology could derive 4
     *   measured it as a regression. Eight of the nine already derive 1 or 2 from `perfCores / 2`, so
     *   this bound changes behaviour on that one part alone. (inter-op stays sequential: one stream,
     *   not a throughput fan-out.) Re-validate per new topology; the heuristic is the default, not a
     *   proof for cores no entry has measured.
     * - **Memory:** the CPU arena is disabled. Phase 7 measured it as pure overhead for this steady,
     *   one-translation-at-a-time workload (−37% process memory, no latency cost) — a property of the
     *   workload, not the device, so it holds across the Arm ecosystem.
     * - **The graph cache is optimized ONNX over one shared weight blob** (Q21, §3.47), not the ORT
     *   flatbuffer, and not the buffer-mapped initializers that shipped between §3.44 and §3.47 —
     *   `use_ort_model_bytes_for_initializers` is an ORT-format-only trick and does not apply to this
     *   artifact, which ORT maps for itself. §3.46 measured every format through the real engine: load,
     *   first inference and steady-state latency all tie, and this layout is **−193 MB of storage and
     *   −333 MB of PSS** because three ~1 MB graphs share one blob instead of re-inlining the decoder
     *   weights twice. Nothing here is device-dependent, so it is not conditioned on [caps].
     * - **Int8 acceleration is automatic.** ORT's MLAS kernels dispatch on HWCAP at runtime, so the
     *   same int8 graphs use plain NEON on an Armv8.0 core and SDOT/i8mm/SME on capable cores with no
     *   config here. The policy therefore does not (and cannot, from Java SessionOptions) toggle ISA
     *   kernels; [caps] informs threads, logging, and future execution-provider selection.
     * - **KleidiAI is disabled on SME parts, which is the one place it is reachable.** Its NEON
     *   `dotprod`/`i8mm` kernels are `qsi4c32p` — 4-bit, and therefore inert for this project's 8-bit
     *   weights — so only its 8-bit `qsi8cxp` SME kernels ever run. Measured on the SM-S948B, three
     *   runs at three temperatures with in-run duplicate controls: those kernels are **10–13% slower**
     *   than MLAS's own at the shipping thread count, and also cost ~10% more CPU and ~35–45 MB of
     *   PSS. On the SM-M315F (no dotprod, no i8mm, no SME) the same A/B is indistinguishable — the
     *   2.1% "effect" was smaller than that run's own 4.0% control spread. See OPTIMIZATION_SUMMARY
     *   §3.38–§3.39.
     *
     *   The predicate is `caps.sme` rather than an unconditional `true` deliberately: every non-SME
     *   device then keeps byte-identical behaviour by construction, instead of relying on the M31
     *   result generalising to parts that have i8mm but no SME, which no run has measured.
     *
     *   This **reverses §3.20's direction**, which priced KleidiAI at 4–9% the other way from two runs
     *   it described as thermally degraded. SME still executes — `simpleperf` finds
     *   `kai_..._sme_mopa` hottest — this says those kernels are slower, not absent.
     */
    fun select(caps: CpuCapabilities): OrtTuning {
        val threads = (caps.performanceCores / 2).coerceIn(1, 2)
        val affinity = affinityString(caps, threads)
        return OrtTuning(
            name = "arm-adaptive(threads=$threads${if (affinity != null) ",affinity" else ""}" +
                "${if (caps.sme) ",noKleidiAI" else ""})",
            intraThreads = threads,
            cpuArena = false,
            // Only SME silicon reaches KleidiAI's 8-bit kernels, and there they are a measured
            // regression (§3.39). Off SME parts this is false and nothing changes.
            disableKleidiAi = caps.sme,
            // Phase 2A: the production path bakes a fully-optimized graph once per install and loads
            // it NO_OPT thereafter, so graph optimization is off every startup after the first. Q21
            // changed the baked format to optimized ONNX over the shared blob; see OnnxModels.
            optCache = true,
            // Phase 3: pin ORT's intra-op workers to the big cluster (null when there is nothing to pin).
            intraOpAffinities = affinity,
        )
    }

    /**
     * Builds the `session.intra_op_thread_affinities` string, or null when affinity would be a no-op.
     *
     * ORT's format is `cpu,cpu;cpu,cpu` — `;` separates threads, `,` lists the CPUs one thread may run
     * on — and it requires exactly `intraThreads - 1` groups, because ORT does not pin the calling
     * (main) thread. Each of those workers is allowed the whole performance cluster (every big-core id),
     * so the scheduler can still balance among big cores but never migrates a worker onto a LITTLE core
     * — which is the jitter source Phase 7 measured.
     *
     * ORT processor ids are **1-based**: `thread_utils.cc` rejects id 0 ("Processor id must start from
     * 1"), so a detected 0-based OS cpu id `n` is emitted as `n + 1`. The detected ids in
     * [CpuCapabilities.performanceCoreIds] stay the real kernel numbering; the +1 is ORT's encoding.
     * Nothing is hard-coded.
     *
     * Null (affinity OFF) when: only one intra thread (no worker to pin), or no distinct big cluster was
     * detected (all cores one frequency, or cpufreq unreadable) — pinning to every core is pointless.
     */
    fun affinityString(caps: CpuCapabilities, intraThreads: Int): String? {
        if (intraThreads <= 1) return null
        if (caps.efficiencyCoreIds.isEmpty() || caps.performanceCoreIds.isEmpty()) return null
        val group = caps.performanceCoreIds.joinToString(",") { (it + 1).toString() } // ORT is 1-based
        return List(intraThreads - 1) { group }.joinToString(";")
    }

    /** The detected CPU, cached. */
    val capabilities: CpuCapabilities by lazy {
        CpuCapabilities.detect().also { logDebug(LogTag.APP) { "CPU ${it.describe()}" } }
    }

    /** The policy for this CPU, cached — the default [OrtTuning] for [OnnxModels]/[MtEngine]. */
    val current: OrtTuning by lazy {
        select(capabilities).also {
            logDebug(LogTag.APP) {
                "ORT policy ${it.name} intra=${it.intraThreads} arena=${it.cpuArena} " +
                    "affinity=${it.intraOpAffinities ?: "OFF"} kleidiAI=${if (it.disableKleidiAi) "OFF" else "on"}"
            }
        }
    }
}
