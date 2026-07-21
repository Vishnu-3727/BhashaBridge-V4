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
    val performanceCores: Int,      // big cluster (top max-frequency)
    val efficiencyCores: Int,       // little cluster
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
        "$architecture cores=$coreCount(perf=$performanceCores,eff=$efficiencyCores) " +
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
            val top = maxFreqs.maxOrNull() ?: 0L
            // Cores at the top frequency are the performance cluster. If cpufreq is unreadable
            // (all zero), treat every core as performance — a safe over-estimate for the thread policy.
            val perf = if (top > 0L) maxFreqs.count { it == top } else cores
            val eff = (cores - perf).coerceAtLeast(0)

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
                performanceCores = perf,
                efficiencyCores = eff,
                neon = neon, fp16 = fp16, dotProduct = dotprod, i8mm = i8mm,
                sve = sve, sve2 = sve2, sme = sme, sme2 = sme2,
            )
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
