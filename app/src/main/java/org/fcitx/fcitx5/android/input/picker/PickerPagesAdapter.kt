/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import splitties.dimensions.dp
import kotlin.math.roundToInt

class PickerPagesAdapter(
    val theme: Theme,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener,
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    recentlyUsedFileName: String,
    bordered: Boolean,
    private val policy: PickerPolicy
) : RecyclerView.Adapter<PickerPagesAdapter.ViewHolder>() {

    class ViewHolder(val keyView: TextKeyView) : RecyclerView.ViewHolder(keyView)

    private val keyAppearance = Appearance.Text(
        displayText = "",
        textSize = density.textSize,
        variant = Variant.Normal,
        border = if (bordered) Border.On else Border.Off
    )

    private val categories: MutableList<PickerData.Category> = mutableListOf(
        PickerData.RecentlyUsedCategory
    )
    private val categoryItems: MutableList<List<String>> = mutableListOf(emptyList())

    private var currentCategory = 1
    private var items: List<String> = emptyList()
    private var allowPopup = true
    private var stripWidth = 0

    private val recentlyUsed = RecentlyUsed(recentlyUsedFileName, density.pageSize)

    private fun buildCategories(data: List<Pair<PickerData.Category, Array<String>>>) {
        data.forEach { (cat, arr) ->
            categories.add(cat)
            categoryItems.add(arr.filter(policy::filter))
        }
    }

    init {
        buildCategories(rawData)
        showCategory(1)
        setHasStableIds(true)
    }

    private fun rebuildCategories() {
        categories.clear()
        categories.add(PickerData.RecentlyUsedCategory)
        categoryItems.clear()
        categoryItems.add(emptyList())
        buildCategories(rawData)
    }

    private var lastInvalidateKey = policy.invalidateKey()

    @SuppressLint("NotifyDataSetChanged")
    fun refreshIfNeeded() {
        val newKey = policy.invalidateKey()
        if (lastInvalidateKey != newKey) {
            lastInvalidateKey = newKey
            rebuildCategories()
            showCategory(currentCategory)
        }
    }

    fun insertRecent(text: String) {
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return
        recentlyUsed.insert(text)
        if (currentCategory == 0) {
            showCategory(0)
        }
    }

    fun getCategoryList(): List<PickerData.Category> = categories

    fun updateStripWidth(width: Int) {
        if (width <= 0 || width == stripWidth) return
        stripWidth = width
        notifyItemRangeChanged(0, itemCount, WidthPayload)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun showCategory(index: Int) {
        val safe = index.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        currentCategory = safe
        allowPopup = safe != 0
        val source = if (safe == 0) recentlyUsed.items else categoryItems.getOrElse(safe) { emptyList() }
        items = toHorizontalGrid(source, density.rowCount, density.columnCount, density.pageSize)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].hashCode().toLong() * 31 + position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val keyView = TextKeyView(parent.context, theme, keyAppearance).apply {
            id = View.generateViewId()
            if (density.autoScale) {
                mainText.apply {
                    scaleMode = AutoScaleTextView.Mode.Proportional
                    setPadding(hMargin, vMargin, hMargin, vMargin)
                }
            }
            val width = columnWidth(parent)
            layoutParams = RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        applyFont(keyView)
        return ViewHolder(keyView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val keyView = holder.keyView
        val raw = items.getOrNull(position).orEmpty()
        applyFont(keyView)
        if (raw.isEmpty()) {
            keyView.isEnabled = false
            keyView.mainText.text = ""
            keyView.setOnClickListener(null)
            keyView.setOnLongClickListener(null)
            keyView.swipeEnabled = false
            keyView.onGestureListener = null
            applyItemWidth(keyView)
            return
        }
        val transformed = if (allowPopup) policy.transform(raw) else raw
        keyView.isEnabled = true
        keyView.mainText.text = transformed
        applyItemWidth(keyView)
        keyView.setOnClickListener {
            keyActionListener.onKeyAction(CommitAction(transformed), Source.Keyboard)
        }
        if (!allowPopup) {
            keyView.setOnLongClickListener(null)
            keyView.swipeEnabled = false
            keyView.onGestureListener = null
            return
        }
        keyView.setOnLongClickListener longClick@{ view ->
            if (view !is KeyView) return@longClick false
            val popup = policy.popup(raw) ?: return@longClick false
            view.updateBounds()
            popupActionListener.onPopupAction(
                PopupAction.ShowKeyboardAction(view.id, popup, view.bounds)
            )
            true
        }
        // RecyclerView owns horizontal drags. Variants stay available by long press;
        // disabling per-key swipe prevents ACTION_CANCEL from being mistaken for a
        // popup selection while the symbol strip is flinging.
        keyView.swipeEnabled = false
        keyView.onGestureListener = null
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(WidthPayload)) {
            applyItemWidth(holder.keyView)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.keyView.setOnClickListener(null)
        holder.keyView.setOnLongClickListener(null)
        holder.keyView.onGestureListener = null
        holder.keyView.swipeEnabled = false
    }

    private fun applyFont(keyView: TextKeyView) {
        val textSize = FontProviders.getFontSize("key_main_font", density.textSize)
        keyView.mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        keyView.mainText.setFontTypeFace("key_main_font")
    }

    private fun applyItemWidth(keyView: TextKeyView) {
        val width = columnWidth(keyView.parent as? ViewGroup ?: keyView)
        if (keyView.layoutParams.width != width) {
            keyView.layoutParams =
                RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun columnWidth(parent: ViewGroup): Int {
        val available = stripWidth.takeIf { it > 0 }
            ?: parent.measuredWidth.takeIf { it > 0 }
            ?: parent.width.takeIf { it > 0 }
            ?: (parent.resources.displayMetrics.widthPixels * StripWidthFraction).roundToInt()
        return (available / density.columnCount).coerceAtLeast(parent.dp(32))
    }

    companion object {
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
        private const val StripWidthFraction = 0.88f
        private val WidthPayload = Any()

        fun toHorizontalGrid(
            source: List<String>,
            rows: Int,
            columns: Int,
            pageSize: Int
        ): List<String> {
            if (source.isEmpty() || rows <= 1 || columns <= 0) return source
            val size = pageSize.coerceAtLeast(rows * columns)
            val pages = (source.size + size - 1) / size
            val out = MutableList(pages * columns * rows) { "" }
            for (p in 0 until pages) {
                for (i in 0 until size) {
                    val srcIndex = p * size + i
                    if (srcIndex >= source.size) break
                    val row = i / columns
                    val col = i % columns
                    if (row >= rows || col >= columns) continue
                    out[(p * columns + col) * rows + row] = source[srcIndex]
                }
            }
            while (out.lastOrNull().isNullOrEmpty()) {
                out.removeAt(out.lastIndex)
            }
            return out
        }
    }
}
