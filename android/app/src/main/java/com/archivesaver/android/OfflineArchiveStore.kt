package com.archivesaver.android

import android.content.Context
import java.io.File

object OfflineArchiveStore {
    private const val ROOT_DIR = "offline_archives"
    private const val INDEX_FILE = "index.html"

    fun prepareDirectory(context: Context, id: String): File {
        val safeId = id.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "archive" }
        return File(File(context.filesDir, ROOT_DIR), safeId).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    fun mediaDirectory(archiveDir: File): File {
        return File(archiveDir, "media").apply { mkdirs() }
    }

    fun writeIndex(archiveDir: File, html: String): File {
        return File(archiveDir, INDEX_FILE).apply {
            parentFile?.mkdirs()
            writeText(prepareHtml(html), Charsets.UTF_8)
        }
    }

    fun hasLocalArchive(path: String): Boolean {
        return path.isNotBlank() && File(path).isFile
    }

    fun sizeBytes(path: String): Long {
        val indexFile = File(path)
        val root = indexFile.parentFile ?: indexFile
        return root.sizeBytes()
    }

    private fun File.sizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun prepareHtml(html: String): String {
        val withoutBase = html.replace(Regex("""(?is)<base\b[^>]*>"""), "")
        return if (Regex("""(?is)<head\b[^>]*>""").containsMatchIn(withoutBase)) {
            withoutBase.replaceFirst(Regex("""(?is)(<head\b[^>]*>)"""), "\$1<base href=\"./\">")
        } else {
            "<base href=\"./\">$withoutBase"
        }
    }
}
