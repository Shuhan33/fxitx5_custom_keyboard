/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

object CacheManager {
    private const val MAX_TRANSIENT_BYTES = 64L * 1024L * 1024L
    private const val MAX_TRANSIENT_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /** Rebuildable files prefer app-specific external storage (SD card when available). */
    fun transientRoot(): File = File(appContext.externalCacheDir ?: appContext.cacheDir, "slei-transient")

    suspend fun trimTransientCaches() = withContext(Dispatchers.IO) {
        val root = transientRoot()
        if (!root.exists()) return@withContext
        val now = System.currentTimeMillis()
        val files = root.walkTopDown().filter(File::isFile).toList()
        files.filter { now - it.lastModified() > MAX_TRANSIENT_AGE_MS }.forEach { runCatching { it.delete() } }
        var total = root.walkTopDown().filter(File::isFile).sumOf(File::length)
        if (total > MAX_TRANSIENT_BYTES) {
            root.walkTopDown().filter(File::isFile).sortedBy(File::lastModified).forEach { file ->
                if (total <= MAX_TRANSIENT_BYTES) return@forEach
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) total -= size
            }
        }
    }

    suspend fun clearTransientCaches() = withContext(Dispatchers.IO) {
        deleteQuietly(appContext.cacheDir)
        appContext.externalCacheDir?.let(::deleteQuietly)
    }

    private fun deleteQuietly(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.walkBottomUp().forEach { file ->
            if (file != dir) runCatching { file.delete() }
        }
    }
}
