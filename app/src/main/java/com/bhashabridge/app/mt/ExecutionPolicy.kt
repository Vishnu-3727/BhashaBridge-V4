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
     * - **Threads:** intra-op parallelism = **half the performance cluster**, bounded to [1, 4]. The
     *   work is a sequence of *small* int8 GEMMs (one token at a time), which is latency-bound and
     *   saturates its intra-op parallelism quickly. Phase 7 measured this directly on the 4-big-core
     *   SM-M315F: 2 threads was the sweet spot; 4 added big.LITTLE scheduler sync/migration jitter for
     *   no median gain, and 8 (all cores, onto the efficiency cluster) collapsed. Half the big cluster
     *   reproduces that optimum (4 perf → 2) and scales conservatively — a single-big-core phone → 1,
     *   an 8-big flagship → 4 — with no device constant. The [1, 4] cap reflects that more than ~4
     *   threads never helps a small-GEMM latency stream. (inter-op stays sequential: one stream, not a
     *   throughput fan-out.) Re-validate the exact count per new topology; the heuristic is the default,
     *   not a proof for cores this phase could not measure.
     * - **Memory:** the CPU arena is disabled. Phase 7 measured it as pure overhead for this steady,
     *   one-translation-at-a-time workload (−37% process memory, no latency cost) — a property of the
     *   workload, not the device, so it holds across the Arm ecosystem.
     * - **Int8 acceleration is automatic.** ORT's MLAS kernels dispatch on HWCAP at runtime, so the
     *   same int8 graphs use plain NEON on an Armv8.0 core and SDOT/i8mm/SME on capable cores with no
     *   config here. The policy therefore does not (and cannot, from Java SessionOptions) toggle ISA
     *   kernels; [caps] informs threads, logging, and future execution-provider selection.
     */
    fun select(caps: CpuCapabilities): OrtTuning {
        val threads = (caps.performanceCores / 2).coerceIn(1, 4)
        val affinity = affinityString(caps, threads)
        return OrtTuning(
            name = "arm-adaptive(threads=$threads${if (affinity != null) ",affinity" else ""})",
            intraThreads = threads,
            cpuArena = false,
            // Phase 2A: the production path bakes a fully-optimized graph once per install and loads
            // it NO_OPT thereafter, so graph optimization is off every startup after the first.
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
                "ORT policy ${it.name} intra=${it.intraThreads} arena=${it.cpuArena} affinity=${it.intraOpAffinities ?: "OFF"}"
            }
        }
    }
}
