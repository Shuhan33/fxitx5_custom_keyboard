/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

/**
 * Japanese 12-key (九键) layout, kept for later refinement.
 * Not shown as a dedicated bottom-row key; switch via the language key
 * once a Japanese engine (e.g. Anthy) is available.
 */
@SuppressLint("ViewConstructor")
class JapaneseKanaKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, ::Layout, { null }) {

    override fun currentLayoutSignature(): String = Name

    companion object {
        const val Name = "Japanese"

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                SymbolKey("あ", 0f),
                SymbolKey("か", 0f),
                SymbolKey("さ", 0f),
                BackspaceKey()
            ),
            listOf(
                SymbolKey("た", 0f),
                SymbolKey("な", 0f),
                SymbolKey("は", 0f),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.15f)
            ),
            listOf(
                SymbolKey("ま", 0f),
                SymbolKey("や", 0f),
                SymbolKey("ら", 0f),
                LayoutSwitchKey("符号", PickerWindow.Key.Symbol.name, 0.15f)
            ),
            listOf(
                LayoutSwitchKey("拼音", TextKeyboard.Name, 0.12f),
                SymbolKey("、", 0.1f),
                SymbolKey("わ", 0f),
                SymbolKey("。", 0.1f),
                ReturnKey()
            )
        )
    }

    val backspace: ImageKeyView? by lazy { findKeyViewById<ImageKeyView>(R.id.button_backspace) }
    val `return`: ImageKeyView? by lazy { findKeyViewById<ImageKeyView>(R.id.button_return) }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`?.img?.imageResource = returnDrawable
    }

    override fun onReturnDrawableOverride(drawable: Drawable?) {
        if (drawable != null) {
            `return`?.img?.setImageDrawable(drawable)
        }
    }
}
