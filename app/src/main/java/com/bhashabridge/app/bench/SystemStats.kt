package com.bhashabridge.app.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * A single-shot snapshot of everything about process and device state that can be read **without
 * root** on a stock Android device — memory, CPU, thermal, battery. Every field is nullable: a null
 * means "this device did not expose it", recorded honestly rather than faked, and the exact reasons
 * accumulate in [unavailable]. Two snapshots (before/after a workload) let the exporter derive deltas
 * — CPU time used, energy drawn, frequency changes — that a single reading cannot give.
 *
 * Pure measurement: reads /proc, /sys, [Debug] and [BatteryManager]; changes nothing.
 */
class SystemStats private constructor(
    val label: String,
    val elapsedRealtimeMs: Long,
    // memory (KB)
    val javaHeapKb: Long?,
    val nativeHeapKb: Long?,
    val totalPssKb: Long?,
    val privateDirtyKb: Long?,
    val sharedDirtyKb: Long?,
    val nativePssKb: Long?,
    val dalvikPssKb: Long?,
    val rssKb: Long?,
    // cpu
    val processCpuTicks: Long?,
    val clockTicksPerSec: Long?,
    val threadCount: Int?,
    val nrMigrations: Long?,
    val voluntaryCtxt: Long?,
    val nonvoluntaryCtxt: Long?,
    val perCoreFreqKhz: List<Long>?,
    // thermal
    val batteryTempC: Double?,
    val thermalZonesC: Map<String, Double>?,
    // battery
    val batteryLevelPct: Int?,
    val batteryStatus: String?,
    val batteryPlugged: String?,
    val voltageMv: Int?,
    val chargeCounterUah: Long?,
    val energyCounterNwh: Long?,
    val currentNowUa: Long?,
    val unavailable: List<String>,
) {
    fun toJson(): String = buildString {
        append('{')
        kv("label", "\"$label\""); comma()
        kv("elapsedRealtimeMs", elapsedRealtimeMs.toString()); comma()
        append("\"memory\":{")
        n("javaHeapKb", javaHeapKb); comma(); n("nativeHeapKb", nativeHeapKb); comma()
        n("totalPssKb", totalPssKb); comma(); n("privateDirtyKb", privateDirtyKb); comma()
        n("sharedDirtyKb", sharedDirtyKb); comma(); n("nativePssKb", nativePssKb); comma()
        n("dalvikPssKb", dalvikPssKb); comma(); n("rssKb", rssKb)
        append("},")
        append("\"cpu\":{")
        n("processCpuTicks", processCpuTicks); comma(); n("clockTicksPerSec", clockTicksPerSec); comma()
        n("threadCount", threadCount?.toLong()); comma(); n("nrMigrations", nrMigrations); comma()
        n("voluntaryCtxt", voluntaryCtxt); comma(); n("nonvoluntaryCtxt", nonvoluntaryCtxt); comma()
        append("\"perCoreFreqKhz\":"); append(perCoreFreqKhz?.joinToString(",", "[", "]") ?: "null")
        append("},")
        append("\"thermal\":{")
        append("\"batteryTempC\":"); append(batteryTempC?.toString() ?: "null"); comma()
        append("\"thermalZonesC\":")
        append(thermalZonesC?.entries?.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" } ?: "null")
        append("},")
        append("\"battery\":{")
        n("levelPct", batteryLevelPct?.toLong()); comma()
        kv("status", batteryStatus?.let { "\"$it\"" } ?: "null"); comma()
        kv("plugged", batteryPlugged?.let { "\"$it\"" } ?: "null"); comma()
        n("voltageMv", voltageMv?.toLong()); comma(); n("chargeCounterUah", chargeCounterUah); comma()
        n("energyCounterNwh", energyCounterNwh); comma(); n("currentNowUa", currentNowUa)
        append("},")
        append("\"unavailable\":")
        append(unavailable.joinToString(",", "[", "]") { "\"$it\"" })
        append('}')
    }

    private fun StringBuilder.kv(k: String, v: String) { append("\"$k\":").append(v) }
    private fun StringBuilder.n(k: String, v: Long?) { append("\"$k\":").append(v?.toString() ?: "null") }
    private fun StringBuilder.comma() { append(',') }

    companion object {
        fun capture(context: Context, label: String): SystemStats {
            val gone = ArrayList<String>()
            fun <T> read(name: String, block: () -> T?): T? =
                runCatching { block() }.getOrNull().also { if (it == null) gone += name }

            val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            fun memStat(key: String) = read("memory.$key") { mi.getMemoryStat(key)?.toLongOrNull() }

            val clkTck = read("cpu.clockTicksPerSec") { Os.sysconf(OsConstants._SC_CLK_TCK) }
            val pageKb = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrDefault(4L)
            val stat = read("cpu.procStat") { File("/proc/self/stat").readText() }
            val statusText = read("cpu.procStatus") { File("/proc/self/status").readText() }
            val schedText = read("cpu.procSched") { File("/proc/self/sched").readText() }

            val battery = read("battery.intent") {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
            val bm = read("battery.manager") { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
            fun bmProp(name: String, id: Int): Long? = read("battery.$name") {
                bm?.getLongProperty(id)?.takeIf { it != Long.MIN_VALUE && it != 0L }
            }

            return SystemStats(
                label = label,
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                javaHeapKb = memStat("summary.java-heap"),
                nativeHeapKb = memStat("summary.native-heap"),
                totalPssKb = read("memory.totalPss") { mi.totalPss.toLong() },
                privateDirtyKb = read("memory.privateDirty") { mi.totalPrivateDirty.toLong() },
                sharedDirtyKb = read("memory.sharedDirty") { mi.totalSharedDirty.toLong() },
                nativePssKb = read("memory.nativePss") { mi.nativePss.toLong() },
                dalvikPssKb = read("memory.dalvikPss") { mi.dalvikPss.toLong() },
                rssKb = read("memory.rss") {
                    File("/proc/self/statm").readText().trim().split(" ")[1].toLong() * pageKb
                },
                processCpuTicks = stat?.let { parseProcStatCpu(it) },
                clockTicksPerSec = clkTck,
                threadCount = statusText?.let { field(it, "Threads:")?.toIntOrNull() },
                nrMigrations = schedText?.let { schedField(it, "nr_migrations") },
                voluntaryCtxt = statusText?.let { field(it, "voluntary_ctxt_switches:")?.toLongOrNull() },
                nonvoluntaryCtxt = statusText?.let { field(it, "nonvoluntary_ctxt_switches:")?.toLongOrNull() },
                perCoreFreqKhz = read("cpu.perCoreFreq") { perCoreFreq() },
                batteryTempC = read("thermal.batteryTemp") {
                    battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
                },
                thermalZonesC = read("thermal.zones") { thermalZones() },
                batteryLevelPct = read("battery.level") {
                    battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
                },
                batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let(::batteryStatusName),
                batteryPlugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.let(::pluggedName),
                voltageMv = read("battery.voltage") {
                    battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it >= 0 }
                },
                chargeCounterUah = bmProp("chargeCounter", BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                energyCounterNwh = bmProp("energyCounter", BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
                currentNowUa = bmProp("currentNow", BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                unavailable = gone,
            )
        }

        private fun parseProcStatCpu(stat: String): Long? {
            // /proc/self/stat: fields after "(comm)" — utime is field 14, stime field 15 (1-based).
            val rparen = stat.lastIndexOf(')')
            if (rparen < 0) return null
            val rest = stat.substring(rparen + 2).trim().split(" ")
            // rest[0] is field 3 (state). utime = field14 -> rest[11], stime = field15 -> rest[12].
            val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
            return utime + stime
        }

        private fun field(text: String, key: String): String? =
            text.lineSequence().firstOrNull { it.startsWith(key) }?.removePrefix(key)?.trim()?.split(Regex("\\s+"))?.firstOrNull()

        private fun schedField(text: String, key: String): Long? =
            text.lineSequence().firstOrNull { it.trimStart().startsWith(key) }
                ?.substringAfter(':')?.trim()?.toDoubleOrNull()?.toLong()

        private fun perCoreFreq(): List<Long> {
            val cores = Runtime.getRuntime().availableProcessors()
            return (0 until cores).map { c ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toLong()
                }.getOrDefault(-1L)
            }
        }

        private fun thermalZones(): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            val base = File("/sys/class/thermal")
            base.listFiles { f -> f.name.startsWith("thermal_zone") }?.sortedBy { it.name }?.forEach { z ->
                val type = runCatching { File(z, "type").readText().trim() }.getOrDefault(z.name)
                val milli = runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull()
                if (milli != null) out[type] = milli / 1000.0
            }
            return out
        }

        private fun batteryStatusName(v: Int) = when (v) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

        private fun pluggedName(v: Int) = when (v) {
            0 -> "unplugged"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unknown"
        }
    }
}
