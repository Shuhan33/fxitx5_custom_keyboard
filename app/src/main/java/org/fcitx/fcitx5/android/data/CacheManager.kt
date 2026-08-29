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
