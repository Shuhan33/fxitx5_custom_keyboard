/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

internal enum class LanguageKeyRoute {
    EnumerateInputMethods,
    ReturnToPreviousInputMethod
}

internal fun languageKeyRoute(japaneseKeyboardActive: Boolean): LanguageKeyRoute =
    if (japaneseKeyboardActive) {
        LanguageKeyRoute.ReturnToPreviousInputMethod
    } else {
        LanguageKeyRoute.EnumerateInputMethods
    }
