/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceSwipeBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    JapaneseToggle(R.string.space_swipe_behavior_japanese_toggle),
    MoveCursor(R.string.space_swipe_move_cursor),
    Disabled(R.string.space_swipe_behavior_disabled)
}
