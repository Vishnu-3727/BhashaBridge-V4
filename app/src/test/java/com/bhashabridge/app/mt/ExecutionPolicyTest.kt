package com.bhashabridge.app.mt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks two rules.
 *
 * The first is what ORT enforces on `session.intra_op_thread_affinities`: the number of `;`-separated
 * groups must equal `intraThreads - 1` (ORT never pins the calling thread). A wrong count throws at
 * session creation, so it is worth a pure-JVM guard rather than only an on-device catch.
 *
 * The second is the thread rule itself. It is a one-line expression that decides a shipping default
 * for every device, and its upper bound has already been wrong once — the old cap of 4 sent the
 * SM-S948B's eight uniform Oryon cores to 4 threads, measured as a regression against 2. Pinning the
 * derivation here means changing that bound cannot happen silently.
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

    @Test fun threadsAreHalfTheBigClusterCappedAtTwo() {
        // perfCores -> expected intra-op threads. The 8-core row is entry #9's uniform-IP Oryon part:
        // half of eight is four, and four measured slower than two on the production path (§2b C1).
        val expected = mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 2, 6 to 2, 8 to 2)
        for ((perfCores, threads) in expected) {
            val c = caps((0 until perfCores).toList(), emptyList())
            assertEquals("perfCores=$perfCores", threads, ExecutionPolicy.select(c).intraThreads)
        }
    }

    @Test fun affinityGroupsMatchTheDerivedThreadCount() {
        // The two rules have to agree: whatever select() derives, the affinity string it builds must
        // carry threads-1 groups or ORT throws at session creation.
        val c = caps(listOf(4, 5, 6, 7), listOf(0, 1, 2, 3))
        val tuning = ExecutionPolicy.select(c)
        assertEquals(2, tuning.intraThreads)
        assertEquals(1, tuning.intraOpAffinities!!.split(";").size)
    }

    @Test fun noAffinityWhenNothingToPin() {
        assertNull("single intra thread has no worker to pin", ExecutionPolicy.affinityString(caps(listOf(0), emptyList()), 1))
        assertNull("no big/LITTLE split => pinning to all cores is pointless",
            ExecutionPolicy.affinityString(caps(listOf(0, 1, 2, 3), emptyList()), 2))
    }
}
