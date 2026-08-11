package com.bhashabridge.app.smoke

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import kotlin.concurrent.thread

/**
 * Purpose:  Samples device state on a fixed cadence *while* a workload runs, and derives what a
 *           before/after pair cannot: when the device started throttling, which cores actually
 *           executed, and how much charge the run cost.
 * Owns:     One daemon sampling thread, started by [start] and joined by [stop].
 * Lifetime: One run phase.
 * Thread:   [start]/[stop] from the runner thread; the sampler owns its own.
 *
 * `SystemStats` already reads every field this needs, but only as a single snapshot. A snapshot
 * cannot see a thermal ramp: a phone that starts at 30 °C and ends at 30 °C may still have spent
 * the middle of the run at 48 °C and clocked down for it, and the median latency that comes out
 * the other side is then a number with no explanation attached. Sampling is the difference between
 * "the p95 was bad" and "the p95 was bad because the big cluster dropped 900 MHz at t=41 s".
 *
 * Cost is deliberately low: a handful of small `/sys` and `/proc` reads every [periodMs] on one
 * background thread. The reads are the same ones `SystemStats` performs once, so the sampler adds
 * no capability, only a time axis.
 */
class CoreSampler(
    private val context: Context,
    private val periodMs: Long = 500L,
) {

    /** One instant. Every field is nullable for the same reason SystemStats' are: not every device exposes it. */
    class Sample(
        val elapsedMs: Long,
        val perCoreKhz: List<Long>,
        /** How many of this process's threads were last seen on each core. Index = cpu id. */
        val threadsPerCore: List<Int>,
        /** This process's CPU time (ticks) since the previous sample. */
        val cpuTicksDelta: Long,
        val batteryTempC: Double?,
        val maxZoneC: Double?,
        val currentNowUa: Long?,
        val chargeUah: Long?,
        val threadCount: Int?,
    )

    private val samples = ArrayList<Sample>(512)
    private var worker: Thread? = null
    @Volatile private var running = false
    private var startedAt = 0L
    private var prevCpuTicks = 0L

    fun start() {
        if (worker != null) return
        samples.clear()
        prevCpuTicks = processCpuTicks()
        startedAt = SystemClock.elapsedRealtime()
        running = true
        worker = thread(name = "bench-sampler", isDaemon = true) {
            while (running) {
                runCatching { samples += sampleOnce() }
                Thread.sleep(periodMs)
            }
        }
    }

    /** Stops sampling and returns everything captured, in order. */
    fun stop(): List<Sample> {
        running = false
        worker?.join(periodMs * 4)
        worker = null
        return samples.toList()
    }

    private fun sampleOnce(): Sample {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val bm = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull()

        val ticks = processCpuTicks()
        val delta = (ticks - prevCpuTicks).coerceAtLeast(0L)
        prevCpuTicks = ticks

        return Sample(
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            perCoreKhz = perCoreKhz(),
            threadsPerCore = threadsPerCore(),
            cpuTicksDelta = delta,
            batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
            maxZoneC = thermalZones().values.maxOrNull(),
            currentNowUa = bmProp(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            chargeUah = bmProp(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            threadCount = runCatching { File("/proc/self/task").list()?.size }.getOrNull(),
        )
    }

    // Long.MIN_VALUE is BatteryManager's "not supported". Zero is NOT filtered out here, unlike in
    // SystemStats: a genuine 0 µA reading is information during a run, and dropping it would make a
    // device that reports honestly look like one that reports nothing.
    private fun bmProp(bm: BatteryManager?, id: Int): Long? = runCatching {
        bm?.getLongProperty(id)?.takeIf { it != Long.MIN_VALUE }
    }.getOrNull()

    // ---- per-core -------------------------------------------------------------------------------

    /**
     * Which cores **this process** was running on, by reading every thread's `processor` field.
     *
     * The obvious source — system-wide per-core jiffies from `/proc/stat` — is not available: SELinux
     * has blocked app access to it since Android 8, and it reads back empty rather than failing, so
     * it silently produces a plausible-looking zero. `/proc/self/task/<tid>/stat` **is** readable,
     * because it is this app's own process, and field 39 is the CPU the thread last ran on.
     *
     * That turns out to be the better measurement anyway. `/proc/stat` would have answered "was this
     * core busy with anything", including the launcher and every system service; this answers "was
     * this core running *the benchmark*" — which is the actual question behind "did the affinity
     * policy keep ORT on the big cluster".
     *
     * It is a sample, not an integral: a thread is credited to the core it was on at this instant.
     * Over hundreds of samples that converges on real occupancy, and the sampling period is chosen
     * so it does.
     */
    private fun threadsPerCore(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val counts = IntArray(cores)
        val tasks = runCatching { File("/proc/self/task").listFiles() }.getOrNull() ?: return counts.toList()
        for (t in tasks) {
            val cpu = runCatching { statField(File(t, "stat").readText(), PROCESSOR_FIELD) }.getOrNull()
            if (cpu != null && cpu in 0 until cores) counts[cpu]++
        }
        return counts.toList()
    }

    /** utime + stime for the whole process, in clock ticks. */
    private fun processCpuTicks(): Long = runCatching {
        val stat = File("/proc/self/stat").readText()
        (statField(stat, UTIME_FIELD) ?: 0) + (statField(stat, STIME_FIELD) ?: 0)
    }.getOrDefault(0L).toLong()

    /**
     * Reads a 1-based field from a `/proc/.../stat` line.
     *
     * Split has to start after the last `)`: field 2 is the thread name in parentheses and can
     * itself contain spaces, which is the classic way this parse goes wrong. After that, `rest[0]`
     * is field 3.
     */
    private fun statField(stat: String, oneBasedField: Int): Int? {
        val rparen = stat.lastIndexOf(')')
        if (rparen < 0) return null
        val rest = stat.substring(rparen + 2).trim().split(' ')
        return rest.getOrNull(oneBasedField - 3)?.toIntOrNull()
    }

    private fun perCoreKhz(): List<Long> {
        val cores = Runtime.getRuntime().availableProcessors()
        return (0 until cores).map { c ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toLong()
            }.getOrDefault(-1L)
        }
    }

    /**
     * Best-effort `/sys/class/thermal` read, abandoned permanently after the first refusal.
     *
     * SELinux denies `untrusted_app` this directory on most modern devices (confirmed on the
     * SM-M315F: `avc: denied { read } ... name="thermal"`). Every attempt writes an audit record to
     * the kernel log, so retrying twice a second for a whole run floods logcat with hundreds of
     * denials and makes the app look like it is doing something wrong. One attempt is enough to
     * learn the answer; battery temperature via BatteryManager is not restricted and remains the
     * thermal signal on those devices.
     */
    private var thermalReadable = true

    private fun thermalZones(): Map<String, Double> {
        if (!thermalReadable) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
        if (zones == null) {
            thermalReadable = false
            return emptyMap()
        }
        zones.sortedBy { it.name }
            .forEach { z ->
                val type = runCatching { File(z, "type").readText().trim() }.getOrDefault(z.name)
                val milli = runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull()
                // Some zones report in °C already, some in milli-°C. Anything above 1000 is milli.
                if (milli != null) out[type] = if (milli > 1000) milli / 1000.0 else milli.toDouble()
            }
        return out
    }

    companion object {

        /**
         * What a sampled run is worth reporting: the peaks, the drift, and the cost.
         *
         * `thermalRiseC` and `throttleRatio` are the two that answer "is this number sustainable".
         * A throttleRatio well under 1.0 means the cores that started the run fast did not stay
         * fast, and any median latency quoted from that run is a blend of two different machines.
         */
        fun summarise(samples: List<Sample>): Map<String, Any?> {
            if (samples.isEmpty()) return emptyMap()

            val temps = samples.mapNotNull { it.batteryTempC }
            val zones = samples.mapNotNull { it.maxZoneC }

            // Charge counters run down, so start-minus-end is the drain. Devices that do not expose
            // the counter simply report null rather than a fabricated zero.
            val charge = samples.mapNotNull { it.chargeUah }
            val drainUah = if (charge.size >= 2) charge.first() - charge.last() else null

            val coreCount = samples.first().perCoreKhz.size
            val peakKhz = (0 until coreCount).map { c ->
                samples.mapNotNull { it.perCoreKhz.getOrNull(c)?.takeIf { k -> k > 0 } }.maxOrNull() ?: -1L
            }

            // Share of sampled thread-instants each core carried. Sums to ~1 across cores, so it
            // reads directly as "where the work went" — the number that says whether the affinity
            // policy held.
            val coreHits = (0 until coreCount).map { c -> samples.sumOf { it.threadsPerCore.getOrElse(c) { 0 } } }
            val totalHits = coreHits.sum().coerceAtLeast(1)
            val coreShare = coreHits.map { round2(it.toDouble() / totalHits) }

            /*
             * Sustained clock as a fraction of the peak this run reached, measured over the last
             * quarter of the samples.
             *
             * The previous form — last sample over first sample — could exceed 1.0 and did (1.14 on
             * the first real run), because the first sample lands before the workload has ramped the
             * governor up. Comparing the settled tail against the peak the device actually achieved
             * cannot exceed 1.0 and means what it says: 1.0 is no throttling, 0.7 is a third of the
             * clock gone.
             */
            val tailStart = (samples.size * 3) / 4
            val tail = samples.drop(tailStart).flatMap { s -> s.perCoreKhz.filter { it > 0 } }
            val peakSum = peakKhz.filter { it > 0 }.sum()
            val tailMeanPerSample = if (tail.isNotEmpty() && samples.size > tailStart) {
                tail.sum().toDouble() / (samples.size - tailStart)
            } else 0.0

            return linkedMapOf(
                "sampleCount" to samples.size,
                "durationMs" to samples.last().elapsedMs,
                "batteryTempStartC" to temps.firstOrNull(),
                "batteryTempPeakC" to temps.maxOrNull(),
                "thermalRiseC" to if (temps.size >= 2) round2(temps.max() - temps.first()) else null,
                "maxThermalZoneC" to zones.maxOrNull(),
                "coreShare" to coreShare,
                "coreThreadHits" to coreHits,
                "peakCoreKhz" to peakKhz,
                "sustainedClockRatio" to if (peakSum > 0 && tailMeanPerSample > 0) {
                    round2((tailMeanPerSample / peakSum).coerceAtMost(1.0))
                } else null,
                "processCpuTicks" to samples.sumOf { it.cpuTicksDelta },
                "batteryDrainUah" to drainUah,
                "meanCurrentUa" to samples.mapNotNull { it.currentNowUa }.takeIf { it.isNotEmpty() }?.average()?.let { round2(it) },
                "peakThreadCount" to samples.mapNotNull { it.threadCount }.maxOrNull(),
            )
        }

        private fun round2(v: Double) = Math.round(v * 100) / 100.0

        // 1-based field indices in /proc/<pid>/stat. `processor` is the CPU the task last ran on.
        private const val UTIME_FIELD = 14
        private const val STIME_FIELD = 15
        private const val PROCESSOR_FIELD = 39
    }
}
