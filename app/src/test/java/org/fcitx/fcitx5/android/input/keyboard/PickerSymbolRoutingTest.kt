/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerSymbolRoutingTest {

    @Test
    fun symbolPickerPreservesActiveChinesePreedit() {
        assertTrue(
            shouldComposePickerSymbol(
                symbolPicker = true,
                chineseInputMethod = true,
                preeditActive = true,
                japaneseModeActive = false
            )
        )
    }

    @Test
    fun otherPickerAndNonCompositionStatesKeepCommitBehavior() {
        assertFalse(shouldComposePickerSymbol(false, true, true, false))
        assertFalse(shouldComposePickerSymbol(true, true, false, false))
        assertFalse(shouldComposePickerSymbol(true, false, true, false))
        assertFalse(shouldComposePickerSymbol(true, true, true, true))
    }
}
