/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

/**
 * QWERTY romaji keyboard for the Kotlin Japanese composer
 * ([org.fcitx.fcitx5.android.input.japanese.JapaneseInputComponent]).
 *
 * Keys only emit plain [KeyAction]s; while this keyboard is shown, KeyboardWindow routes every
 * action through the Japanese component first, so nothing typed here reaches the fcitx engine.
 * The bottom row mirrors the default Chinese layout (符号, 123, comma, space, period, language,
 * return) so positions stay the same when cycling languages.
 */
@SuppressLint("ViewConstructor")
class JapaneseKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, { buildLayout() }) {

    override fun currentLayoutSignature(): String =
        "$Name:${AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()}"

    companion object {
        const val Name = "JapaneseRomaji"
        const val SpaceLabel = "日本語"

        /** Long vowel mark, also produced by `-` in romaji. */
        const val ChoonKey = "ー"

        private fun letter(c: String, alt: String) = AlphabetKey(
            character = c,
            punctuation = alt,
            displayText = c,
            popup = arrayOf(
                KeyDef.Popup.AltPreview(c, alt),
                KeyDef.Popup.Keyboard.Explicit(arrayOf(alt))
            )
        )

        private fun punctuationKey(main: String, alt: String, extra: Array<String>) = AlphabetKey(
            character = main,
            punctuation = alt,
            displayText = main,
            popup = arrayOf(
                KeyDef.Popup.AltPreview(main, alt),
                KeyDef.Popup.Keyboard.Explicit(extra)
            )
        )

        fun buildLayout(): List<List<KeyDef>> {
            val showLangSwitch = AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
            return listOf(
                listOf(
                    letter("q", "1"), letter("w", "2"), letter("e", "3"), letter("r", "4"),
                    letter("t", "5"), letter("y", "6"), letter("u", "7"), letter("i", "8"),
                    letter("o", "9"), letter("p", "0")
                ),
                listOf(
                    letter("a", "「"), letter("s", "」"), letter("d", "『"), letter("f", "』"),
                    letter("g", "・"), letter("h", "…"), letter("j", "※"), letter("k", "（"),
                    letter("l", "）")
                ),
                listOf(
                    AlphabetKey(
                        character = ChoonKey,
                        punctuation = "〜",
                        displayText = ChoonKey,
                        variant = KeyDef.Appearance.Variant.Alternative,
                        weight = 0.15f,
                        popup = arrayOf(
                            KeyDef.Popup.AltPreview(ChoonKey, "〜"),
                            KeyDef.Popup.Keyboard.Explicit(arrayOf("〜", "～", "－"))
                        )
                    ),
                    letter("z", "！"), letter("x", "？"), letter("c", "："), letter("v", "_"),
                    letter("b", "；"), letter("n", "/"), letter("m", "@"),
                    BackspaceKey()
                ),
                listOf(
                    LayoutSwitchKey("符号", PickerWindow.Key.Symbol.name, 0.12f),
                    LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f),
                    punctuationKey("、", "！", arrayOf("，", ",", "！")),
                    SpaceKey(displayText = SpaceLabel),
                    punctuationKey("。", "？", arrayOf("．", ".", "？", "…")),
                    *if (showLangSwitch) arrayOf(LanguageKey()) else emptyArray(),
                    ReturnKey()
                )
            )
        }
    }

    private val returnKey: ImageKeyView? get() = findKeyViewById(R.id.button_return)

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        returnKey?.img?.imageResource = returnDrawable
    }

    override fun onReturnDrawableOverride(drawable: Drawable?) {
        if (drawable != null) {
            returnKey?.img?.setImageDrawable(drawable)
        }
    }
}
