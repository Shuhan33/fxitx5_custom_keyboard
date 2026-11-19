/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Whether a selected symbol should be inserted as a literal into the current pinyin composition.
 * Prediction lists without active preedit, other input methods, and the local Japanese mode keep
 * their existing commit behavior.
 */
internal fun shouldComposePickerSymbol(
    symbolPicker: Boolean,
    chineseInputMethod: Boolean,
    preeditActive: Boolean,
    japaneseModeActive: Boolean
): Boolean = symbolPicker && chineseInputMethod && preeditActive && !japaneseModeActive
