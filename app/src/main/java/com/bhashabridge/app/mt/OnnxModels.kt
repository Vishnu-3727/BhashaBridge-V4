package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bhashabridge.app.Direction
import java.io.File
import java.io.FileOutputStream

/**
 * Purpose:  Owns the encoder and decoder [OrtSession]s for one [Direction], and the one-time copy
 *           of their `.onnx` assets to app-private storage (ONNX Runtime loads from a file path).
 * Owns:     Two native `OrtSession`s. The [OrtEnvironment] is a process-wide singleton per ONNX
 *           Runtime's contract and is NOT closed here.
 * Lifetime: Created and released by [com.bhashabridge.app.BhashaBridgeApp] at process scope. Nothing
 *           else may construct or release it (R4.4). This is the fix for v3.4.1's L2 leak: one
 *           owner, one [release], a real call site.
 * Thread:   Construction blocks until both sessions load. `run()` on the exposed sessions is
 *           synchronous on the caller's thread.
 *
 * Session options are left at ONNX Runtime defaults — no thread-count, XNNPACK, or NEON tuning.
 * Phase 5 is correctness only; runtime tuning is Phase 7 and must be measured, not guessed.
 */
class OnnxModels(context: Context, direction: Direction) {

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        val (encAsset, decAsset) = when (direction) {
            Direction.EN_TO_HI -> "encoder_model_int8.onnx" to "decoder_model_int8.onnx"
            Direction.HI_TO_EN -> "hi_en_encoder_int8.onnx" to "hi_en_decoder_int8.onnx"
        }
        // Sequential, not parallel: v3.4.1 loaded these on a throwaway thread pool to halve startup,
        // which is a startup optimisation this phase does not do.
        encoder = env.createSession(copyToFiles(context, encAsset))
        decoder = env.createSession(copyToFiles(context, decAsset))
    }

    fun encoderSession(): OrtSession = encoder
    fun decoderSession(): OrtSession = decoder

    /** Closes both native sessions. Its only call site is the process-scoped owner. */
    fun release() {
        encoder.close()
        decoder.close()
    }

    /** Idempotent asset→filesDir copy; skips if present. 256 KB buffer for the 70–200 MB files. */
    private fun copyToFiles(context: Context, name: String): String {
        val file = File(context.filesDir, name)
        if (!file.exists()) {
            val buf = ByteArray(262_144)
            context.assets.open(name).use { input ->
                FileOutputStream(file).use { output ->
                    var n = input.read(buf)
                    while (n != -1) { output.write(buf, 0, n); n = input.read(buf) }
                }
            }
        }
        return file.absolutePath
    }
}
