package com.bhashabridge.app.speech

import android.content.Context
import com.bhashabridge.app.BuildConfig
import java.io.File
import java.io.IOException

/**
 * Purpose:  Unpacks an asset folder to app-private storage and returns its path.
 * Owns:     Nothing.
 * Lifetime: Process
 * Thread:   Blocking file I/O — never call from the main thread.
 *
 * Vosk loads a model from a real directory path; it cannot read an APK asset stream. Idempotent:
 * once the destination exists and is non-empty the multi-hundred-file copy is skipped, so the cost
 * is paid once per install.
 */
internal object AssetFolder {

    /**
     * The unpack is staged and renamed rather than written in place. "Destination exists and is
     * non-empty" was the only completeness test available, and a copy interrupted after its first
     * file satisfies it permanently: Vosk would then load a model directory missing most of its
     * files, on every launch, with no way back short of clearing app data.
     *
     * It is also not a *freshness* test, which is the second half of the same problem. `filesDir`
     * survives app updates, so once a model has been unpacked the app never looks at that asset
     * again — ship a retuned `conf/model.conf` and every existing install keeps decoding with the
     * old one, silently, forever. That is not hypothetical: it is exactly how the Hindi decode-width
     * change would have failed to reach anybody who already had the app.
     *
     * So the published directory carries a [stamp] beside it, and a mismatch re-unpacks.
     */
    fun unpack(context: Context, assetFolder: String): String {
        val dest = File(context.filesDir, assetFolder)
        val stampFile = File(context.filesDir, "$assetFolder.stamp")
        val expected = stamp(context, assetFolder)
        val published = dest.exists() && dest.listFiles()?.isNotEmpty() == true
        if (published && runCatching { stampFile.readText() }.getOrNull() == expected) {
            return dest.absolutePath
        }
        val staging = File(context.filesDir, "$assetFolder.part")
        staging.deleteRecursively()
        try {
            copy(context, assetFolder, staging)
            dest.deleteRecursively()
            if (!staging.renameTo(dest)) throw IOException("could not publish unpacked asset $assetFolder")
            // Written only after the rename succeeds: a stamp beside a directory that was never
            // published would make the next launch trust an unpack that did not happen.
            stampFile.writeText(expected)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        return dest.absolutePath
    }

    /**
     * Identity of the asset folder as shipped: the build, plus the contents of the `conf/` files.
     *
     * `conf/` and not the whole folder because the alternative is unaffordable and the cheap
     * shortcuts do not work here. Hashing every file means reading 56–81 MB on every launch, to
     * detect a change that happens once per release. `AssetManager.openFd` — how `OnnxModels` gets
     * its lengths without reading — throws on these, because `noCompress` covers `onnx`/`bin`/`pb`
     * and an acoustic model is `.mdl`/`.fst`/`.conf`.
     *
     * What is left is the pair that actually earns its cost: `VERSION_CODE` catches a new build, and
     * the few hundred bytes of `conf/` catch a retune shipped inside one. A changed acoustic model
     * under an unchanged version code is the one case this misses, and it cannot occur without an
     * app rebuild.
     */
    private fun stamp(context: Context, assetFolder: String): String {
        val conf = runCatching {
            (context.assets.list("$assetFolder/conf") ?: emptyArray()).sorted().joinToString("|") { name ->
                context.assets.open("$assetFolder/conf/$name").use { it.readBytes() }
                    .contentHashCode().toString()
            }
        }.getOrDefault("")
        return "${BuildConfig.VERSION_CODE}|$conf"
    }

    /**
     * `AssetManager` has no isDirectory(): `list(path)` returns an empty array for a leaf file and
     * the child names for a directory. That is the only file/folder test available, so it is the
     * one this walk uses.
     */
    private fun copy(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            children.forEach { copy(context, "$assetPath/$it", File(dest, it)) }
        }
    }
}
