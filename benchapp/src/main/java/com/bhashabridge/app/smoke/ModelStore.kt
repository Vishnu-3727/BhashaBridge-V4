package com.bhashabridge.app.smoke

import android.content.Context
import com.bhashabridge.app.Direction
import java.io.File

/**
 * Purpose:  Finds sideloaded model graphs and puts them where `OnnxModels` already looks.
 * Owns:     Nothing; it moves files.
 * Lifetime: Process.
 * Thread:   Blocking file I/O — never call from the main thread.
 *
 * The APK ships no models, which is what keeps it a few MB and installable on any device. The MT
 * phase therefore reads graphs the operator pushed to the app's external files directory — a
 * location any app can write to for itself with **no storage permission**, and that `adb push`
 * reaches without root.
 *
 * The trick that avoids modifying `:app`: `OnnxModels.loadSourceUncached` resolves a graph as
 * `File(context.filesDir, name)` and only extracts from assets when that file is **absent**. Placing
 * the sideloaded graph there ahead of time means the production loader runs unmodified against it
 * and never discovers there were no assets behind it.
 */
object ModelStore {

    /** Graph and vocabulary files each direction needs, using `OnnxModels`' own asset names. */
    private val EN_HI = listOf(
        "encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx",
        "dict.SRC.json", "dict.TGT.json",
    )
    private val HI_EN = listOf(
        "hi_en_encoder_int8.onnx", "hi_en_decoder_init_int8.onnx", "hi_en_decoder_step_int8.onnx",
        "dict.SRC_HI.json", "dict.TGT_EN.json",
    )

    fun required(direction: Direction): List<String> =
        if (direction == Direction.EN_TO_HI) EN_HI else HI_EN

    /** Where the operator pushes graphs. Readable and writable without any permission. */
    fun stagingDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    /** The `adb push` line to show when models are missing, with the real on-device path filled in. */
    fun pushHint(context: Context): String =
        "adb push <asset> ${stagingDir(context).absolutePath}/"

    /** Names still missing for [direction], staging and filesDir both considered. */
    fun missing(context: Context, direction: Direction): List<String> {
        val staging = stagingDir(context)
        return required(direction).filter { name ->
            !File(staging, name).isNonEmpty() && !File(context.filesDir, name).isNonEmpty()
        }
    }

    fun isReady(context: Context, direction: Direction): Boolean = missing(context, direction).isEmpty()

    // ── Vosk acoustic model ──────────────────────────────────────────────────────────────────────

    /**
     * The acoustic model is a *directory*, and the same trick works on it for the same reason.
     *
     * `VoskModels` asks `AssetFolder.unpack`, which returns immediately if `filesDir/<folder>` exists
     * and is non-empty and only falls back to the APK's assets otherwise. So placing the sideloaded
     * model directory there means the production loader runs unmodified and never learns that this
     * APK ships no acoustic model.
     */
    private fun speechFolder(direction: Direction) =
        if (direction == Direction.EN_TO_HI) "model" else "model-hi"

    /** A staged Vosk model needs these; the rest of the directory is optional per model. */
    private val VOSK_MARKERS = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst")

    fun isSpeechReady(context: Context, direction: Direction): Boolean {
        val folder = speechFolder(direction)
        return listOf(File(context.filesDir, folder), File(stagingDir(context), folder))
            .any { root -> VOSK_MARKERS.all { File(root, it).isNonEmpty() } }
    }

    fun speechPushHint(context: Context, direction: Direction): String =
        "adb push app/src/main/assets/${speechFolder(direction)} ${stagingDir(context).absolutePath}/"

    /**
     * Links the staged acoustic model into `filesDir` where `AssetFolder` looks, mirroring [stage].
     *
     * Hard links per file, falling back to a copy. The link is attempted rather than relied on:
     * `getExternalFilesDir` and `filesDir` are usually **different** mounts — emulated external
     * storage against `/data` — so `Os.link` returns `EXDEV` and the copy is what actually runs on
     * most devices. It costs one 56 MB (English) or 81 MB (Hindi) copy, once, and already-published
     * models are skipped so a re-run costs nothing.
     */
    fun stageSpeech(context: Context, direction: Direction): Long {
        val folder = speechFolder(direction)
        val dest = File(context.filesDir, folder)
        if (VOSK_MARKERS.all { File(dest, it).isNonEmpty() }) return dirSize(dest)
        val src = File(stagingDir(context), folder)
        if (!src.isDirectory) return 0L
        // Published under a staging name and renamed, for the reason AssetFolder itself documents:
        // "directory exists and is non-empty" is the only completeness test either side has, and a
        // half-copied directory satisfies it permanently.
        val partial = File(context.filesDir, "$folder.part")
        partial.deleteRecursively()
        src.walkTopDown().forEach { file ->
            val target = File(partial, file.toRelativeString(src))
            if (file.isDirectory) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                if (!link(file, target)) file.copyTo(target, overwrite = true)
            }
        }
        dest.deleteRecursively()
        if (!partial.renameTo(dest)) {
            partial.deleteRecursively()
            return 0L
        }
        return dirSize(dest)
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Links each staged graph into `filesDir` so the stock loader finds it, and reports the bytes
     * involved.
     *
     * A **hard link** first, falling back to a copy: these files are 75–200 MB each, so where the
     * link takes, the MT phase starts instantly and no second copy of half a gigabyte exists. It
     * often does not take — `getExternalFilesDir` and `filesDir` are usually different mounts, and
     * `Os.link` across them returns `EXDEV` — so the copy is the common path, paid once per device.
     */
    fun stage(context: Context, direction: Direction): Long {
        val staging = stagingDir(context)
        var bytes = 0L
        for (name in required(direction)) {
            val dest = File(context.filesDir, name)
            if (dest.isNonEmpty()) { bytes += dest.length(); continue }
            val src = File(staging, name)
            if (!src.isNonEmpty()) continue
            link(src, dest) || src.copyTo(dest, overwrite = true).let { true }
            bytes += dest.length()
        }
        return bytes
    }

    private fun link(src: File, dest: File): Boolean = runCatching {
        android.system.Os.link(src.absolutePath, dest.absolutePath)
        true
    }.getOrDefault(false)

    /**
     * Removes what [stage] put in `filesDir`, plus anything the ORT loader derived from it.
     *
     * Worth having a button for: these graphs are ~470 MB per direction and the bench app has no
     * other reason to keep them between runs. The `.ort` and `.ort.stamp` files are the production
     * loader's baked cache, and deleting them is also how a cold-start measurement is made cold.
     */
    fun clearStaged(context: Context): Long {
        var freed = 0L
        (context.filesDir.listFiles() ?: emptyArray()).forEach { f ->
            // The acoustic models are directories, and they are the largest thing this app leaves
            // behind after a speech run — 137 MB for both — so "clear" has to mean them too.
            if (f.isDirectory && (f.name == "model" || f.name == "model-hi" ||
                    f.name == "model.part" || f.name == "model-hi.part")
            ) {
                freed += dirSize(f)
                f.deleteRecursively()
            } else if (f.name.endsWith(".onnx") || f.name.endsWith(".ort") ||
                f.name.endsWith(".ort.stamp") || f.name.endsWith(".json")
            ) {
                freed += f.length()
                f.delete()
            }
        }
        return freed
    }

    private fun File.isNonEmpty() = exists() && length() > 0L
}
