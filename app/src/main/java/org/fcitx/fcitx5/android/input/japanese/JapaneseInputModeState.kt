/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.japanese

/** Holds the exact fcitx IME that remains underneath the temporary Japanese keyboard. */
internal class JapaneseInputModeState {
    private var anchor: String? = null

    val isActive: Boolean
        get() = anchor != null

    val anchorIme: String?
        get() = anchor

    fun enter(anchorIme: String): Boolean {
        if (anchorIme.isBlank() || isActive) return false
        anchor = anchorIme
        return true
    }

    fun deactivate(): String? = anchor.also { anchor = null }
}
