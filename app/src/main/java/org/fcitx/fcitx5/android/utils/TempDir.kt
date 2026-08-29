/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.data.CacheManager
import java.io.File

inline fun <T> withTempDir(block: (File) -> T): T {
    val dir = CacheManager.transientRoot().resolve(System.currentTimeMillis().toString()).also {
        it.mkdirs()
    }
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
