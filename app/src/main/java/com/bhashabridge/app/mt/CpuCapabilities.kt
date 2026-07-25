package com.bhashabridge.app.mt

import java.io.File

/**
 * What the current Arm CPU can do, detected at runtime — the input to [ExecutionPolicy]. Nothing here
 * is device-specific: every field is read from the kernel (`/proc/cpuinfo`, `/sys` cpufreq), so the
 * same binary characterises whatever core it lands on, from an Armv8.0 A53 to an Armv9 SME2 core.
 *
 * ISA flags come from the `Features` line of `/proc/cpuinfo` (the kernel's HWCAP names). Topology
 * (performance vs efficiency cores) comes from per-core max frequency: the cores at the top frequency
 * are the big cluster. Both are best-effort — if a file is unreadable the field degrades to a safe
 * default (all cores treated as one cluster, feature absent) rather than throwing.
 */
data class CpuCapabilities(
    val architecture: String,       // e.g. "ARMv8.0", "ARMv8.6", "ARMv9"
    val coreCount: Int,
    val performanceCores: Int,      // big cluster size (top max-frequency)
    val efficiencyCores: Int,       // little cluster size
    // The actual logical CPU ids (0-based, as the kernel numbers them in /sys), not assumed ranges —
    // on this Exynos the big cluster is not guaranteed to be cpu4-7. Thread affinity is built from
    // these, so no CPU id is ever hard-coded. performanceCoreIds.size == performanceCores.
    val performanceCoreIds: List<Int>,
    val efficiencyCoreIds: List<Int>,
    val neon: Boolean,              // Advanced SIMD (asimd)
    val fp16: Boolean,              // half-precision arithmetic (asimdhp/fphp)
    val dotProduct: Boolean,        // SDOT/UDOT (asimddp) — int8 acceleration, Armv8.2+
    val i8mm: Boolean,              // int8 matrix multiply — Armv8.6+
    val sve: Boolean,               // Scalable Vector Extension
    val sve2: Boolean,              // SVE2 — Armv9
    val sme: Boolean,               // Scalable Matrix Extension
    val sme2: Boolean,              // SME2 — newest matrix acceleration
) {
    /** One-line summary for logs and the report. */
    fun describe(): String =
        "$architecture cores=$coreCount(perf=$performanceCores$performanceCoreIds,eff=$efficiencyCores$efficiencyCoreIds) " +
            "neon=$neon fp16=$fp16 dotprod=$dotProduct i8mm=$i8mm sve=$sve sve2=$sve2 sme=$sme sme2=$sme2"

    companion object {

        /** Detect the running CPU's capabilities. Cheap; call once and cache (see [ExecutionPolicy]). */
        fun detect(): CpuCapabilities {
            val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
            val feats = Regex("(?im)^Features\\s*:\\s*(.*)$")
                .find(cpuinfo)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.toHashSet()
                ?: hashSetOf()
            fun has(vararg names: String) = names.any { it in feats }

            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val maxFreqs = (0 until cores).map { core ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLong()
                }.getOrDefault(0L)
            }
            val (perfIds, effIds) = perfEffSplit(maxFreqs)

            val neon = has("asimd", "neon")
            val fp16 = has("asimdhp", "fphp")
            val dotprod = has("asimddp")
            val i8mm = has("i8mm")
            val sve = has("sve")
            val sve2 = has("sve2")
            val sme = has("sme")
            val sme2 = has("sme2")

            return CpuCapabilities(
                architecture = archLabel(cpuinfo, dotprod, i8mm, sve2, sme2),
                coreCount = cores,
                performanceCores = perfIds.size,
                efficiencyCores = effIds.size,
                performanceCoreIds = perfIds,
                efficiencyCoreIds = effIds,
                neon = neon, fp16 = fp16, dotProduct = dotprod, i8mm = i8mm,
                sve = sve, sve2 = sve2, sme = sme, sme2 = sme2,
            )
        }

        /**
         * Split logical cores into (performanceIds, efficiencyIds) by max frequency. The **lowest**
         * frequency tier is the efficiency cluster; **every tier above it** is performance.
         *
         * This is the generalisation past 2-cluster big.LITTLE. The old rule ("only cores at the single
         * top frequency are performance") misclassifies a 3-cluster Armv9 part: on a Snapdragon 8 Gen 1
         * (Cortex-X2 @ 2995 MHz + 3× A710 @ 2496 + 4× A510 @ 1785) it counted only the X2 as performance
         * and lumped the three A710 mid cores in with the A510 littles, so inference ran single-threaded.
         * Splitting at the *bottom* tier keeps big.LITTLE identical (one perf tier, one eff tier) while
         * putting the A710 mids where they belong (performance). Robust to any number of upper tiers
         * (e.g. Dimensity's two-frequency prime cluster): only the little tier is efficiency.
         *
         * One distinct frequency, or all unreadable (0) — no distinct little cluster exists: treat every
         * core as performance (a safe over-estimate for the thread count; affinity is then skipped for
         * lack of a big/LITTLE split). Unreadable (0) cores never join the performance pool. Cpu ids are
         * the real kernel numbering (the list index). Visible for test; see `CpuCapabilitiesTest`.
         */
        internal fun perfEffSplit(maxFreqs: List<Long>): Pair<List<Int>, List<Int>> {
            val distinct = maxFreqs.filter { it > 0L }.distinct()
            if (distinct.size <= 1) return maxFreqs.indices.toList() to emptyList()
            val littleFreq = distinct.min()
            val perf = maxFreqs.indices.filter { maxFreqs[it] > littleFreq }
            val eff = maxFreqs.indices.filter { it !in perf }
            return perf to eff
        }

        /**
         * Best-effort architecture label. `/proc/cpuinfo` only reports the base (`CPU architecture: 8`),
         * so the minor version is inferred from feature flags: SME2/SVE2 ⇒ Armv9, i8mm ⇒ v8.6,
         * dotprod ⇒ v8.2, else v8.0.
         */
        private fun archLabel(
            cpuinfo: String, dotprod: Boolean, i8mm: Boolean, sve2: Boolean, sme2: Boolean,
        ): String {
            val base = Regex("(?im)^CPU architecture\\s*:\\s*(\\d+)")
                .find(cpuinfo)?.groupValues?.get(1)?.toIntOrNull() ?: 8
            return when {
                base >= 9 || sve2 || sme2 -> "ARMv9"
                i8mm -> "ARMv8.6"
                dotprod -> "ARMv8.2"
                else -> "ARMv8.0"
            }
        }
    }
}
