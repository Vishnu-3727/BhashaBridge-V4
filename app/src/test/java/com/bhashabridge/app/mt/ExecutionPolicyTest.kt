package com.bhashabridge.app.mt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the one rule ORT enforces on `session.intra_op_thread_affinities`: the number of `;`-separated
 * groups must equal `intraThreads - 1` (ORT never pins the calling thread). A wrong count throws at
 * session creation, so it is worth a pure-JVM guard rather than only an on-device catch.
 */
class ExecutionPolicyTest {

    // A 4-big (ids 4-7) + 4-LITTLE (ids 0-3) layout, whatever the real numbering happens to be.
    private fun caps(perfIds: List<Int>, effIds: List<Int>) = CpuCapabilities(
        architecture = "ARMv8.0", coreCount = perfIds.size + effIds.size,
        performanceCores = perfIds.size, efficiencyCores = effIds.size,
        performanceCoreIds = perfIds, efficiencyCoreIds = effIds,
        neon = true, fp16 = false, dotProduct = false, i8mm = false,
        sve = false, sve2 = false, sme = false, sme2 = false,
    )

    @Test fun oneWorkerPinnedToWholeBigCluster() {
        // OS cpu ids 4-7 emitted in ORT's 1-based scheme as 5-8 (ORT rejects id 0).
        assertEquals("5,6,7,8", ExecutionPolicy.affinityString(caps(listOf(4, 5, 6, 7), listOf(0, 1, 2, 3)), 2))
    }

    @Test fun groupCountAlwaysEqualsThreadsMinusOne() {
        val c = caps(listOf(4, 5, 6, 7), listOf(0, 1, 2, 3))
        for (threads in 2..4) {
            val groups = ExecutionPolicy.affinityString(c, threads)!!.split(";")
            assertEquals("threads=$threads must yield ${threads - 1} affinity groups", threads - 1, groups.size)
        }
    }

    @Test fun noAffinityWhenNothingToPin() {
        assertNull("single intra thread has no worker to pin", ExecutionPolicy.affinityString(caps(listOf(0), emptyList()), 1))
        assertNull("no big/LITTLE split => pinning to all cores is pointless",
            ExecutionPolicy.affinityString(caps(listOf(0, 1, 2, 3), emptyList()), 2))
    }
}
