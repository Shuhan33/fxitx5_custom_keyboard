/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * Shared picker density presets.
 *
 * The picker is rendered by [PickerPagesAdapter] in one horizontally scrolling
 * RecyclerView. Keeping only the presets here avoids retaining the obsolete,
 * page-at-a-time picker implementation.
 */
object PickerPageUi {
    enum class Density(
        val pageSize: Int,
        val columnCount: Int,
        val rowCount: Int,
        val textSize: Float,
        val autoScale: Boolean
    ) {
        High(28, 10, 3, 19f, false),
        Medium(20, 7, 3, 23.7f, false),
        Low(12, 4, 3, 19f, true)
    }
}
