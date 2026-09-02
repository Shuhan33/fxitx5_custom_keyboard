/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.view
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class PickerLayout(context: Context, theme: Theme, switchKey: KeyDef) :
    ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, switchKey: KeyDef) : BaseKeyboard(
        context, theme, {listOf(
            listOf(
                LayoutSwitchKey("键盘", TextKeyboard.Name),
                PunctuationKey(","),
                switchKey,
                SpaceKey(),
                PunctuationKey("."),
                ReturnKey()
            )
        )}
    ) {

        override fun currentLayoutSignature(): String = "Picker"

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.FcitxKeyAction(symbol))
            )
        )

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

    val embeddedKeyboard = Keyboard(context, theme, switchKey)

    var allowStripScroll = true

    val strip = view(::RecyclerView) {
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        itemAnimator = null
        isNestedScrollingEnabled = false
        addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (!allowStripScroll && e.actionMasked != MotionEvent.ACTION_DOWN) {
                    return true
                }
                return false
            }
        })
    }

    private val backspaceAppearance = Appearance.Image(
        src = R.drawable.ic_baseline_backspace_24,
        variant = Variant.Alternative,
        border = Border.Off,
        viewId = R.id.button_backspace
    )

    val backspace = ImageKeyView(context, theme, backspaceAppearance, iconSlot = "keys.backspace").apply {
        repeatEnabled = true
        val action: (View) -> Unit = {
            embeddedKeyboard.keyActionListener?.onKeyAction(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)),
                Source.Keyboard
            )
        }
        setOnClickListener { action(it) }
        onRepeatListener = action
    }

    val tabsUi = PickerTabsUi(context, theme)

    init {
        add(strip, lParams {
            topOfParent()
            leftOfParent()
            rightToLeftOf(backspace)
            above(embeddedKeyboard)
        })
        add(backspace, lParams {
            topOfParent()
            above(embeddedKeyboard)
            rightOfParent()
            matchConstraintPercentWidth = 0.12f
        })
        add(embeddedKeyboard, lParams {
            below(strip)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
    }
}
