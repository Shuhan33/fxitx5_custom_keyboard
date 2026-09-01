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
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
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
    private val onUndoSelection: (() -> Unit)? = null
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

    private var insertedCursorPosition = -1

    /**
     * The icon sends a Pinyin BackSpace event, whose native meaning while a segment is selected
     * is `PinyinContext::cancel()`: pop only the last selected segment and preserve all raw input.
     * It deliberately does not use preedit cursor movement, which can reset the composition.
     */
    private val undoSelectionSpan = object : ClickableSpan() {
        override fun onClick(widget: View) {
            onUndoSelection?.invoke()
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = theme.keyTextColor
            ds.isUnderlineText = false
        }
    }

    init {
        upView.movementMethod = LinkMovementMethod.getInstance()
        upView.highlightColor = android.graphics.Color.TRANSPARENT
    }

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
        val preedit = inputPanel.preedit.toString()
        val selectedPrefixLength = preedit.indexOfFirst {
            it in 'a'..'z' || it in 'A'..'Z' || it == '\''
        }.let { if (it < 0) preedit.length else it }
        val activeBkg = theme.genericActiveBackgroundColor
        val baseUpString: SpannedString
        val baseUpCursor: Int
        if (inputPanel.auxUp.isEmpty()) {
            baseUpString = inputPanel.preedit.toSpannedString(activeBkg)
            baseUpCursor = inputPanel.preedit.cursor
        } else {
            baseUpString = buildSpannedString {
                append(inputPanel.auxUp.toSpannedString(activeBkg))
                append(inputPanel.preedit.toSpannedString(activeBkg))
            }
            baseUpCursor = inputPanel.preedit.cursor.let {
                if (it < 0) it
                else inputPanel.auxUp.length + it
            }
        }
        val indicatorPosition = inputPanel.auxUp.length + selectedPrefixLength
        val showUndo = onUndoSelection != null && selectedPrefixLength > 0
        val upString = if (showUndo) buildSpannedString {
            append(baseUpString, 0, indicatorPosition)
            val start = length
            append(" ↶ ")
            setSpan(undoSelectionSpan, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.72f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(SuperscriptSpan(), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(baseUpString, indicatorPosition, baseUpString.length)
        } else baseUpString
        val upCursor = if (showUndo && baseUpCursor >= indicatorPosition) {
            baseUpCursor + UNDO_INDICATOR_LENGTH
        } else {
            baseUpCursor
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

    private companion object {
        const val UNDO_INDICATOR_LENGTH = 3
    }
}
