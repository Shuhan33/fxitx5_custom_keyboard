/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.Spanned
import android.text.SpannedString
import android.text.style.DynamicDrawableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout

open class PreeditUi(
    override val ctx: Context,
    private val theme: Theme,
    private val setupTextView: (TextView.() -> Unit)? = null,
    private val onPreeditClick: ((Int) -> Unit)? = null
) : Ui {

    class CursorSpan(ctx: Context, @ColorInt color: Int, metrics: Paint.FontMetricsInt) :
        DynamicDrawableSpan() {
        private val drawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
            setBounds(0, metrics.ascent, ctx.dp(1), metrics.bottom)
        }

        override fun getDrawable() = drawable
    }

    private val cursorSpan by lazy {
        CursorSpan(ctx, theme.keyTextColor, upView.paint.fontMetricsInt)
    }

    private fun createTextView() = textView {
        setTextColor(theme.keyTextColor)
        setupTextView?.invoke(this)
        // Apply preedit font settings after external setup to avoid being overridden
        // by candidate window style hooks.
        val fontSize = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "preedit_font", 16f
        )
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize)
        typeface = FontProviders.resolveTypeface("preedit_font", typeface)
    }

    private val upView = createTextView()

    private val downView = createTextView()

    private var upAuxLength = 0
    private var upPreeditLength = 0
    private var insertedCursorPosition = -1

    init {
        if (onPreeditClick != null) {
            upView.setOnTouchListener { view, event ->
                if (upPreeditLength <= 0) return@setOnTouchListener false
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Consume DOWN so Android keeps this TextView as the touch target and
                    // delivers the matching UP from which we calculate the character.
                    return@setOnTouchListener true
                }
                if (event.actionMasked != MotionEvent.ACTION_UP) {
                    return@setOnTouchListener event.actionMasked != MotionEvent.ACTION_CANCEL
                }
                val textView = view as TextView
                val layout = textView.layout ?: return@setOnTouchListener false
                val x = event.x - textView.totalPaddingLeft + textView.scrollX
                val y = event.y - textView.totalPaddingTop + textView.scrollY
                val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
                var renderedOffset = layout.getOffsetForHorizontal(line, x)
                // update() inserts a one-character drawable cursor into the rendered text.
                // Remove that synthetic character before converting the click to a preedit
                // code-point position understood by Fcitx.
                if (insertedCursorPosition >= 0 && renderedOffset > insertedCursorPosition) {
                    renderedOffset--
                }
                val preeditOffset = (renderedOffset - upAuxLength).coerceIn(0, upPreeditLength)
                val source = inputPanelPreedit
                var codePointOffset = source.codePointCount(0, preeditOffset.coerceAtMost(source.length))
                // Converted Pinyin segments form the leading non-ASCII part of preedit
                // (for example 例'zi). A tap on that glyph should target the character's
                // leading boundary; Fcitx then cancels that selection and restores li'zi.
                val selectedPrefixLength = source.indexOfFirst {
                    it in 'a'..'z' || it in 'A'..'Z' || it == '\''
                }.let { if (it < 0) source.length else it }
                if (preeditOffset <= selectedPrefixLength && selectedPrefixLength > 0) {
                    codePointOffset = (codePointOffset - 1).coerceAtLeast(0)
                }
                onPreeditClick.invoke(codePointOffset)
                true
            }
        }
    }

    private var inputPanelPreedit: String = ""

    var visible = false
        private set

    val actualContentWidth: Int
        get() {
            if (!visible || root.visibility != View.VISIBLE) return 0
            val upLayout = upView.layout
            val downLayout = downView.layout
            val upWidth = upLayout?.let { layout ->
                if (upView.visibility == View.VISIBLE && layout.lineCount > 0) {
                    var maxW = 0f
                    for (i in 0 until layout.lineCount) maxW = maxW.coerceAtLeast(layout.getLineWidth(i))
                    maxW.toInt() + upView.paddingLeft + upView.paddingRight
                } else 0
            } ?: 0
            val downWidth = downLayout?.let { layout ->
                if (downView.visibility == View.VISIBLE && layout.lineCount > 0) {
                    var maxW = 0f
                    for (i in 0 until layout.lineCount) maxW = maxW.coerceAtLeast(layout.getLineWidth(i))
                    maxW.toInt() + downView.paddingLeft + downView.paddingRight
                } else 0
            } ?: 0
            return upWidth.coerceAtLeast(downWidth)
        }

    override val root: View = verticalLayout {
        add(upView, lParams())
        add(downView, lParams())
    }

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        inputPanelPreedit = inputPanel.preedit.toString()
        upAuxLength = inputPanel.auxUp.length
        upPreeditLength = inputPanelPreedit.length
        val activeBkg = theme.genericActiveBackgroundColor
        val upString: SpannedString
        val upCursor: Int
        if (inputPanel.auxUp.isEmpty()) {
            upString = inputPanel.preedit.toSpannedString(activeBkg)
            upCursor = inputPanel.preedit.cursor
        } else {
            upString = buildSpannedString {
                append(inputPanel.auxUp.toSpannedString(activeBkg))
                append(inputPanel.preedit.toSpannedString(activeBkg))
            }
            upCursor = inputPanel.preedit.cursor.let {
                if (it < 0) it
                else inputPanel.auxUp.length + it
            }
        }
        val downString = inputPanel.auxDown.toSpannedString(activeBkg)
        val hasUp = upString.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        visible = hasUp || hasDown
        if (!visible) {
            updateTextView(upView, "", false)
            updateTextView(downView, "", false)
            return
        }
        val upStringWithCursor = if (upCursor < 0 || upCursor == upString.length) {
            insertedCursorPosition = -1
            upString
        } else buildSpannedString {
            insertedCursorPosition = upCursor
            if (upCursor > 0) append(upString, 0, upCursor)
            append('|')
            setSpan(cursorSpan, upCursor, upCursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(upString, upCursor, upString.length)
        }
        updateTextView(upView, upStringWithCursor, hasUp)
        updateTextView(downView, downString, hasDown)
    }
}
