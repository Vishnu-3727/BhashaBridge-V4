package com.bhashabridge.app.smoke

import android.content.Context
import android.os.Build
import com.bhashabridge.app.bench.SystemStats
import com.bhashabridge.app.mt.CpuCapabilities
import com.bhashabridge.app.mt.OrtTuning
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Purpose:  Assembles one run into a single JSON document and writes it where the operator can get
 *           it off the device.
 * Owns:     Nothing.
 * Lifetime: Stateless.
 * Thread:   Any.
 *
 * Schema is `bb-smoke/1`, deliberately a sibling of `BenchmarkSuiteTest`'s `bb-bench/1` rather than
 * an extension of it: the two measure overlapping things by different means, and quietly reusing the
 * name would let a downstream parser mix them. `device`, `systemBefore` and `systemAfter` keep the
 * same shape as `bb-bench/1` so anything already reading those blocks works unchanged.
 *
 * Hand-rolled rather than org.json for one reason that matters here: `Locale.ROOT` on every number.
 * A Hindi or German device locale formats a decimal with a comma, which produces JSON no parser
 * accepts — silent corruption of a benchmark that only shows up on the devices this app most wants
 * to run on. `Metrics` learned the same lesson; see its `ms()`.
 */
object BenchReport {

    private const val SCHEMA = "bb-smoke/1"

    fun build(
        caps: CpuCapabilities,
        policy: OrtTuning,
        preset: BenchRunner.Preset,
        synthetic: List<SyntheticWorkload.KernelResult>,
        mt: MtWorkload.Result?,
        speech: SpeechWorkload.Result?,
        samplerSummary: Map<String, Any?>,
        samples: List<CoreSampler.Sample>,
        before: SystemStats,
        after: SystemStats,
        missingModels: List<String>,
    ): String = buildString(8192) {
        append("{\"schema\":\"").append(SCHEMA).append('"')
        append(",\"timestampMs\":").append(System.currentTimeMillis())

        // The budgets, not just the preset name: a reader comparing two reports needs to see that
        // one ran 8 iterations and the other 60 without holding this file's table in their head.
        // Absent in reports written before presets existed, which is the same as `standard`.
        append(",\"preset\":{")
        append("\"name\":\"").append(esc(preset.name.lowercase(Locale.ROOT))).append('"')
        append(",\"syntheticMinMs\":").append(preset.syntheticMinMs)
        append(",\"mtIterations\":").append(preset.mtIterations)
        append(",\"speechIterations\":").append(preset.speechIterations)
        append(",\"sustainedSeconds\":").append(preset.sustainedSeconds)
        append('}')

        append(",\"device\":{")
        append("\"model\":\"").append(esc(Build.MODEL)).append('"')
        append(",\"manufacturer\":\"").append(esc(Build.MANUFACTURER)).append('"')
        append(",\"board\":\"").append(esc(Build.BOARD)).append('"')
        append(",\"hardware\":\"").append(esc(Build.HARDWARE)).append('"')
        append(",\"androidRelease\":\"").append(esc(Build.VERSION.RELEASE)).append('"')
        append(",\"androidSdk\":").append(Build.VERSION.SDK_INT)
        append(",\"abi\":\"").append(esc(Build.SUPPORTED_ABIS.firstOrNull() ?: "")).append('"')
        append('}')

        append(",\"cpu\":{")
        append("\"architecture\":\"").append(esc(caps.architecture)).append('"')
        append(",\"coreCount\":").append(caps.coreCount)
        append(",\"performanceCores\":").append(caps.performanceCores)
        append(",\"efficiencyCores\":").append(caps.efficiencyCores)
        append(",\"performanceCoreIds\":").append(caps.performanceCoreIds.joinToString(",", "[", "]"))
        append(",\"efficiencyCoreIds\":").append(caps.efficiencyCoreIds.joinToString(",", "[", "]"))
        append(",\"isa\":{")
        append("\"neon\":").append(caps.neon)
        append(",\"fp16\":").append(caps.fp16)
        append(",\"dotProduct\":").append(caps.dotProduct)
        append(",\"i8mm\":").append(caps.i8mm)
        append(",\"sve\":").append(caps.sve)
        append(",\"sve2\":").append(caps.sve2)
        append(",\"sme\":").append(caps.sme)
        append(",\"sme2\":").append(caps.sme2)
        append("}}")

        append(",\"ortPolicy\":{")
        append("\"name\":\"").append(esc(policy.name)).append('"')
        append(",\"intraThreads\":").append(policy.intraThreads ?: 0)
        append(",\"cpuArena\":").append(policy.cpuArena ?: true)
        append(",\"affinity\":").append(policy.intraOpAffinities?.let { "\"${esc(it)}\"" } ?: "null")
        append('}')

        append(",\"synthetic\":[")
        synthetic.forEachIndexed { i, k ->
            if (i > 0) append(',')
            append("{\"kernel\":\"").append(esc(k.name)).append('"')
            append(",\"threads\":").append(k.threads)
            append(",\"opsPerSec\":").append(num(k.opsPerSec))
            append(",\"perIterationMs\":").append(k.perIterationMs.toJson())
            // Present so the reader can confirm the loop was not optimised away.
            append(",\"checksum\":").append(k.checksum)
            append('}')
        }
        append(']')

        append(",\"mt\":")
        if (mt == null) {
            append("{\"ran\":false,\"missingModels\":")
            append(missingModels.joinToString(",", "[", "]") { "\"${esc(it)}\"" })
            append('}')
        } else {
            append("{\"ran\":true")
            append(",\"policyName\":\"").append(esc(mt.policyName)).append('"')
            append(",\"intraThreads\":").append(mt.intraThreads ?: 0)
            append(",\"affinity\":").append(mt.affinity?.let { "\"${esc(it)}\"" } ?: "null")
            append(",\"sessionLoadMs\":").append(mt.sessionLoadMs)
            append(",\"tokensIn\":").append(mt.tokensIn)
            append(",\"tokensOut\":").append(mt.tokensOut)
            append(",\"tokensPerSec\":").append(num(mt.tokensPerSec))
            append(",\"endToEndMs\":").append(mt.endToEnd.toJson())
            append(",\"encoderMs\":").append(mt.encoderMs.toJson())
            append(",\"decoderInitMs\":").append(mt.decoderInitMs.toJson())
            append(",\"decoderStepMs\":").append(mt.decoderStepMs.toJson())
            append(",\"sampleInput\":\"").append(esc(mt.sampleInput)).append('"')
            append(",\"sampleOutput\":\"").append(esc(mt.sampleOutput)).append('"')
            append(",\"kleidiAi\":")
            mt.kleidiAi?.let {
                append("{\"onMs\":").append(num(it.onMs))
                append(",\"offMs\":").append(num(it.offMs))
                append(",\"speedup\":").append(num(it.speedup)).append('}')
            } ?: append("null")
            append(",\"sustained\":")
            mt.sustained?.let {
                append("{\"windowMedians\":")
                append(it.windowMedians.joinToString(",", "[", "]") { v -> num(v) })
                append(",\"firstWindowMedian\":").append(num(it.firstWindowMedian))
                append(",\"lastWindowMedian\":").append(num(it.lastWindowMedian))
                append(",\"degradation\":").append(num(it.degradation)).append('}')
            } ?: append("null")
            append('}')
        }

        append(",\"speech\":")
        if (speech == null) {
            append("{\"ran\":false}")
        } else {
            append("{\"ran\":true")
            append(",\"modelLoadMs\":").append(speech.modelLoadMs)
            append(",\"audioSeconds\":").append(num(speech.audioSeconds))
            append(",\"recognitionMs\":").append(speech.recognition.toJson())
            append(",\"msPerAudioSecond\":").append(num(speech.msPerAudioSecond))
            // The number the phase exists to produce. Above 1.0 the recogniser loses to live speech.
            append(",\"realtimeFactor\":").append(num(speech.realtimeFactor))
            append(",\"transcript\":\"").append(esc(speech.transcript)).append('"')
            // Without the decode width the realtime factor is not comparable across devices, since
            // the two shipped models do not use the same one.
            append(",\"decodeConfig\":{")
            speech.decodeConfig.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"')
            }
            append('}')
            append(",\"leak\":")
            speech.leak?.let {
                append("{\"cycles\":").append(it.cycles)
                append(",\"fdDelta\":").append(it.fdDelta)
                append(",\"threadDelta\":").append(it.threadDelta)
                append(",\"nativeKbDelta\":").append(it.nativeKbDelta)
                append(",\"pssKbDelta\":").append(it.pssKbDelta)
                append(",\"leaking\":").append(it.leaking).append('}')
            } ?: append("null")
            append('}')
        }

        append(",\"sampling\":").append(mapToJson(samplerSummary))

        // The full series, so a reader can plot the thermal ramp rather than trust the summary.
        append(",\"samples\":[")
        samples.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"tMs\":").append(s.elapsedMs)
            append(",\"coreKhz\":").append(s.perCoreKhz.joinToString(",", "[", "]"))
            append(",\"threadsPerCore\":").append(s.threadsPerCore.joinToString(",", "[", "]"))
            append(",\"cpuTicks\":").append(s.cpuTicksDelta)
            append(",\"batteryTempC\":").append(s.batteryTempC?.let { num(it) } ?: "null")
            append(",\"maxZoneC\":").append(s.maxZoneC?.let { num(it) } ?: "null")
            append(",\"currentUa\":").append(s.currentNowUa ?: "null")
            append(",\"threads\":").append(s.threadCount ?: "null")
            append('}')
        }
        append(']')

        append(",\"systemBefore\":").append(before.toJson())
        append(",\"systemAfter\":").append(after.toJson())
        append('}')
    }

    /** Writes to external files, which `adb pull` reaches and the share sheet can attach. */
    fun write(context: Context, json: String): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val f = File(dir, "smoke_${Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "")}_$stamp.json")
        f.writeText(json)
        f
    }.getOrNull()

    private fun mapToJson(map: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) append(',')
            first = false
            append('"').append(esc(k)).append("\":").append(valueToJson(v))
        }
        append('}')
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null -> "null"
        is Double -> num(v)
        is Float -> num(v.toDouble())
        is Number -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { valueToJson(it) }
        else -> "\"${esc(v.toString())}\""
    }

    /** Locale.ROOT and NaN/Infinity guarded — both produce JSON no parser accepts. */
    private fun num(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "null" else String.format(Locale.ROOT, "%.3f", v)

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
    }
}
