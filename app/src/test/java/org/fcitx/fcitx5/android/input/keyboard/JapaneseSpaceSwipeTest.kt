/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.japanese.JapaneseInputModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseSpaceSwipeTest {

    @Test
    fun ordinaryLanguageKeyDoesNotEnterJapaneseCycle() {
        assertEquals(LanguageKeyRoute.EnumerateInputMethods, languageKeyRoute(false))
        assertEquals(LanguageKeyRoute.ReturnToPreviousInputMethod, languageKeyRoute(true))
    }

    @Test
    fun japaneseModeReturnsToTheExactChineseOrEnglishAnchor() {
        listOf("rime", "keyboard-us").forEach { anchor ->
            val state = JapaneseInputModeState()
            assertTrue(state.enter(anchor))
            assertTrue(state.isActive)
            assertEquals(anchor, state.anchorIme)
            assertFalse(state.enter("another-ime"))
            assertEquals(anchor, state.deactivate())
            assertFalse(state.isActive)
            assertNull(state.anchorIme)
        }
    }

    @Test
    fun oneHorizontalSwipeConsumesMotionAndTogglesOnlyOnRelease() {
        val tracker = SpaceSwipeGestureTracker()
        tracker.onDown(0f, 0f)
        repeat(4) { step ->
            assertEquals(
                SpaceSwipeOutcome.Consume,
                tracker.onMove(
                    SpaceSwipeBehavior.JapaneseToggle,
                    1,
                    0,
                    x = (step + 1) * 36f,
                    y = 0f
                )
            )
        }
        assertEquals(
            SpaceSwipeOutcome.ToggleJapanese,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = false)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = false)
        )
    }

    @Test
    fun cancelledHorizontalSwipeIsConsumedWithoutTogglingOrLeakingLatch() {
        val tracker = SpaceSwipeGestureTracker()
        tracker.onDown(0f, 0f)
        assertEquals(
            SpaceSwipeOutcome.Consume,
            tracker.onMove(SpaceSwipeBehavior.JapaneseToggle, -1, 0, x = -36f, y = 0f)
        )
        assertEquals(
            SpaceSwipeOutcome.Consume,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = true)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = false)
        )
    }

    @Test
    fun verticalSwipeKeepsItsCursorActionAndDoesNotToggleJapanese() {
        val tracker = SpaceSwipeGestureTracker()
        tracker.onDown(0f, 0f)
        assertEquals(
            SpaceSwipeOutcome.MoveCursor(0, -1),
            tracker.onMove(SpaceSwipeBehavior.JapaneseToggle, 0, -1, x = 2f, y = -16f)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onMove(SpaceSwipeBehavior.JapaneseToggle, 1, 0, x = 38f, y = -17f)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = false)
        )
    }

    @Test
    fun subThresholdFingerDriftRemainsATapAndDoesNotToggle() {
        val tracker = SpaceSwipeGestureTracker()
        tracker.onDown(10f, 10f)
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onMove(SpaceSwipeBehavior.JapaneseToggle, 0, 0, x = 24f, y = 13f)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = false)
        )
    }

    @Test
    fun extraPointerCancelsJapaneseSwipeButLeavesTapUnconsumed() {
        val tracker = SpaceSwipeGestureTracker()
        tracker.onDown(10f, 10f)
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onMove(SpaceSwipeBehavior.JapaneseToggle, 0, 0, x = 16f, y = 12f)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.JapaneseToggle, cancelled = true)
        )
    }

    @Test
    fun cursorAndDisabledOptionsAreExplicit() {
        val tracker = SpaceSwipeGestureTracker()
        tracker.onDown(0f, 0f)
        assertEquals(
            SpaceSwipeOutcome.MoveCursor(-2, 0),
            tracker.onMove(SpaceSwipeBehavior.MoveCursor, -2, 0, x = -20f, y = 0f)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.MoveCursor, cancelled = false)
        )

        tracker.onDown(0f, 0f)
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onMove(SpaceSwipeBehavior.Disabled, 1, 0, x = 36f, y = 0f)
        )
        assertEquals(
            SpaceSwipeOutcome.None,
            tracker.onUp(SpaceSwipeBehavior.Disabled, cancelled = false)
        )
    }

    @Test
    fun japaneseSpaceLabelIsJapanese() {
        assertEquals("日本語", JapaneseKeyboard.SpaceLabel)
    }
}
