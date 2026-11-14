/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs

internal sealed interface SpaceSwipeOutcome {
    data class MoveCursor(val countX: Int, val countY: Int) : SpaceSwipeOutcome
    data object Consume : SpaceSwipeOutcome
    data object ToggleJapanese : SpaceSwipeOutcome
    data object None : SpaceSwipeOutcome
}

/**
 * Classifies one space-key gesture. Japanese mode changes only on release so repeated Move
 * callbacks cannot toggle twice or detach the view while it is still receiving motion events.
 */
internal class SpaceSwipeGestureTracker {
    private enum class Axis { Horizontal, Vertical }

    private var lockedAxis: Axis? = null
    private var horizontalJapaneseSwipe = false
    private var downX = 0f
    private var downY = 0f

    fun onDown(x: Float = 0f, y: Float = 0f) {
        lockedAxis = null
        horizontalJapaneseSwipe = false
        downX = x
        downY = y
    }

    fun onMove(
        behavior: SpaceSwipeBehavior,
        countX: Int,
        countY: Int,
        x: Float,
        y: Float
    ): SpaceSwipeOutcome {
        if (lockedAxis == null && (countX != 0 || countY != 0)) {
            val horizontalDistance = if (behavior == SpaceSwipeBehavior.JapaneseToggle) {
                abs(x - downX)
            } else {
                abs(countX.toFloat())
            }
            val verticalDistance = if (behavior == SpaceSwipeBehavior.JapaneseToggle) {
                abs(y - downY)
            } else {
                abs(countY.toFloat())
            }
            lockedAxis = if (horizontalDistance >= verticalDistance) Axis.Horizontal else Axis.Vertical
        }
        return when (lockedAxis) {
            Axis.Horizontal -> {
                if (countX == 0) return SpaceSwipeOutcome.None
                when (behavior) {
                    SpaceSwipeBehavior.JapaneseToggle -> {
                        horizontalJapaneseSwipe = true
                        SpaceSwipeOutcome.Consume
                    }
                    SpaceSwipeBehavior.MoveCursor -> SpaceSwipeOutcome.MoveCursor(countX, 0)
                    SpaceSwipeBehavior.Disabled -> SpaceSwipeOutcome.None
                }
            }
            Axis.Vertical -> {
                if (countY != 0 && behavior != SpaceSwipeBehavior.Disabled) {
                    SpaceSwipeOutcome.MoveCursor(0, countY)
                } else {
                    SpaceSwipeOutcome.None
                }
            }
            null -> SpaceSwipeOutcome.None
        }
    }

    fun onUp(behavior: SpaceSwipeBehavior, cancelled: Boolean): SpaceSwipeOutcome {
        val outcome = when {
            behavior == SpaceSwipeBehavior.JapaneseToggle && horizontalJapaneseSwipe && !cancelled ->
                SpaceSwipeOutcome.ToggleJapanese
            behavior == SpaceSwipeBehavior.JapaneseToggle && horizontalJapaneseSwipe ->
                SpaceSwipeOutcome.Consume
            else -> SpaceSwipeOutcome.None
        }
        onDown()
        return outcome
    }
}
