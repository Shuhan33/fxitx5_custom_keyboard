/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

enum class AuxBarPosition { Top, Bottom, Left, Right, AbovePreedit }

data class AuxBarConfig(
    val position: AuxBarPosition,
    val sizePercent: Float,
    /** Keep layout-provided keys visible even when the engine publishes tab actions. */
    val alwaysShowCustomKeys: Boolean = false,
    /** Number of custom keys shown before scrolling; null keeps normal key sizing. */
    val scrollableVisibleKeyCount: Int? = null,
    /** Render the vertical custom-key list as one continuous colored rail. */
    val continuousCustomKeyRail: Boolean = false
)
