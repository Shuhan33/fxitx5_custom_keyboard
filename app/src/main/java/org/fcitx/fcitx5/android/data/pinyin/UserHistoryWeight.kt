/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlin.math.log2
import kotlin.math.min

/**
 * Maps how many times a word has been committed to a mix weight.
 *
 * Must stay in sync with `userHistoryWeightForCount` in libime
 * `userlanguagemodel.cpp`.
 *
 * n=1 → 0.10, n=2 → 0.20, n=4 → 0.30, n=8 → 0.40, then clamped to [cap].
 */
object UserHistoryWeight {
    fun forCount(commitCount: Int, capPercent: Int): Float {
        if (commitCount <= 0) return 0f
        val cap = (capPercent / 100f).coerceIn(0.05f, 0.95f)
        val grown = 0.1f * (1f + log2(commitCount.toFloat()))
        return min(cap, grown)
    }
}
