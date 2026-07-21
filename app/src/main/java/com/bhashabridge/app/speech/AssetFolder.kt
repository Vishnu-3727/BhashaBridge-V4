package com.bhashabridge.app.speech

import android.content.Context
import java.io.File

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

    fun unpack(context: Context, assetFolder: String): String {
        val dest = File(context.filesDir, assetFolder)
        if (dest.exists() && dest.listFiles()?.isNotEmpty() == true) return dest.absolutePath
        copy(context, assetFolder, dest)
        return dest.absolutePath
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
