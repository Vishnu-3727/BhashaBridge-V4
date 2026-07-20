package com.bhashabridge.app

import android.util.Log

/**
 * Purpose:  Subsystem log tags and the debug-gated log call every subsystem uses.
 * Owns:     Nothing.
 * Lifetime: Process
 * Thread:   Any.
 */

/**
 * One tag per subsystem (R8.1). The `BB.` prefix makes `adb logcat -s BB.*` show this app
 * and nothing else.
 */
object LogTag {
    const val APP = "BB.App"
    const val MT = "BB.MT"
    const val SPEECH = "BB.Speech"
    const val UI = "BB.UI"
    const val BENCH = "BB.Bench"
}

/**
 * Debug-only log. Compiled out of release builds entirely.
 *
 * The message is a lambda, not a String, and this function is inline: in release,
 * `BuildConfig.DEBUG` is a compile-time `false`, so the call, the lambda, and every string
 * concatenation inside it are eliminated rather than merely skipped. That makes two rules
 * structural instead of aspirational:
 *
 *  - R8.2: user speech and translation text can never reach a release log, because the string
 *    is never built. V3.4.1 logged `"[$direction] $text -> $result"` on every translation.
 *  - R8.4 / CODING_STANDARDS 9.2: a stray debug log inside the decode loop costs no allocation
 *    in release, so it cannot silently undo the zero-allocation budget.
 */
inline fun logDebug(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(tag, message())
}

/**
 * Degraded but recovered. [why] must state why continuing is safe — R8.5 bans silent recovery,
 * and a warning that does not say why it is survivable is the same thing with extra noise.
 */
fun logWarn(tag: String, why: String, cause: Throwable? = null) {
    if (cause != null) Log.w(tag, why, cause) else Log.w(tag, why)
}

/** Broken, user affected. Always logged, in every build type. */
fun logError(tag: String, what: String, cause: Throwable? = null) {
    if (cause != null) Log.e(tag, what, cause) else Log.e(tag, what)
}
