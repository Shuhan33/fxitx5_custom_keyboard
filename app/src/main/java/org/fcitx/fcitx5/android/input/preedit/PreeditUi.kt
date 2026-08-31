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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private val onUndoSelection: ((Int) -> Unit)? = null
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
    private var undoSelectionPosition = -1

    /**
     * A dedicated target is intentionally used instead of making the preedit text clickable.
     * Moving the Fcitx cursor from an arbitrary glyph can reset the whole composition for some
     * Pinyin states. This button always targets the last selected code point, which asks Pinyin
     * to undo exactly one selected segment.
     */
    private val undoSelectionView = createTextView().apply {
        text = "↶"
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        includeFontPadding = false
        setPadding(dp(3), 0, dp(3), 0)
        contentDescription = "撤销上一次选词"
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        setOnClickListener {
            undoSelectionPosition.takeIf { position -> position >= 0 }
                ?.let { position -> onUndoSelection?.invoke(position) }
        }
    }

    private val upContainer = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
        addView(
            upView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            undoSelectionView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private var selectedPrefixLength = 0

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
        clipChildren = false
        add(upContainer, lParams())
        add(downView, lParams())
    }

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        val preedit = inputPanel.preedit.toString()
        selectedPrefixLength = preedit.indexOfFirst {
            it in 'a'..'z' || it in 'A'..'Z' || it == '\''
        }.let { if (it < 0) preedit.length else it }
        val selectedCodePoints = preedit.codePointCount(0, selectedPrefixLength)
        undoSelectionPosition = if (selectedCodePoints > 0) selectedCodePoints - 1 else -1
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
            undoSelectionView.visibility = View.GONE
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
        updateUndoSelectionIndicator(inputPanel.auxUp.length)
    }

    private fun updateUndoSelectionIndicator(auxUpLength: Int) {
        if (undoSelectionPosition < 0 || upView.visibility != View.VISIBLE) {
            undoSelectionView.visibility = View.GONE
            return
        }
        undoSelectionView.visibility = View.VISIBLE
        upContainer.post {
            if (undoSelectionPosition < 0 || upView.visibility != View.VISIBLE) return@post
            val layout = upView.layout ?: return@post
            var selectedEnd = (auxUpLength + selectedPrefixLength).coerceAtMost(upView.text.length)
            if (insertedCursorPosition in 0 until selectedEnd) selectedEnd++
            val line = layout.getLineForOffset(selectedEnd.coerceAtMost(upView.text.length))
            val x = layout.getPrimaryHorizontal(selectedEnd.coerceAtMost(upView.text.length))
            undoSelectionView.translationX =
                (upView.totalPaddingLeft + x - undoSelectionView.measuredWidth * 0.45f)
                    .coerceAtLeast(0f)
            undoSelectionView.translationY =
                (layout.getLineTop(line) - ctx.dp(2)).toFloat()
                    .coerceAtLeast(-ctx.dp(2).toFloat())
            undoSelectionView.bringToFront()
        }
    }
}
