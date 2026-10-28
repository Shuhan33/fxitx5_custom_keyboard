/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei (vertical browsing rework)
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp
import kotlin.math.roundToInt

/**
 * Picker contents rendered as one continuously scrolling vertical grid.
 *
 * Every category starts with a bold section header row, so users locate
 * symbols by scrolling vertically and reading headers, instead of paging
 * horizontally through dense grids (previous behaviour).
 */
class PickerPagesAdapter(
    val theme: Theme,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener,
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    recentlyUsedFileName: String,
    bordered: Boolean,
    private val policy: PickerPolicy
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class Entry(
        val categoryIndex: Int,
        val isHeader: Boolean,
        val text: String,
        val display: String
    )

    class ItemViewHolder(val keyView: TextKeyView) : RecyclerView.ViewHolder(keyView)

    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

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

    private var entries: List<Entry> = emptyList()
    private var anchors: Map<Int, Int> = emptyMap()
    private var cellSize = 0
    private var rowHeight = 0
    private var headerHeight = 0
    private var lastStripWidth = 0
    private var lastStripHeight = 0

    private val recentlyUsed = RecentlyUsed(recentlyUsedFileName, density.pageSize)
    private var cachedTextSize = FontProviders.getFontSize("key_main_font", density.textSize)
    private var cachedTypeface: Typeface = FontProviders.resolveTypeface("key_main_font")

    private fun buildCategories(data: List<Pair<PickerData.Category, Array<String>>>) {
        data.forEach { (cat, arr) ->
            categories.add(cat)
            categoryItems.add(arr.filter(policy::filter))
        }
    }

    private fun sourceOf(categoryIndex: Int): List<String> =
        if (categoryIndex == 0) recentlyUsed.items
        else categoryItems.getOrElse(categoryIndex) { emptyList() }

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuildEntries() {
        val list = mutableListOf<Entry>()
        val anchorMap = mutableMapOf<Int, Int>()
        categories.forEachIndexed { index, _ ->
            anchorMap[index] = list.size
            list.add(Entry(index, true, "", ""))
            sourceOf(index).forEach { text ->
                val display = if (index == 0) text else policy.transform(text)
                list.add(Entry(index, false, text, display))
            }
        }
        entries = list
        anchors = anchorMap
        notifyDataSetChanged()
    }

    init {
        buildCategories(rawData)
        rebuildEntries()
        setHasStableIds(true)
    }

    private var lastInvalidateKey = policy.invalidateKey()

    @SuppressLint("NotifyDataSetChanged")
    fun refreshIfNeeded() {
        val newKey = policy.invalidateKey()
        if (lastInvalidateKey != newKey) {
            lastInvalidateKey = newKey
            categories.clear()
            categories.add(PickerData.RecentlyUsedCategory)
            categoryItems.clear()
            categoryItems.add(emptyList())
            buildCategories(rawData)
            rebuildEntries()
        }
    }

    fun insertRecent(text: String) {
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return
        recentlyUsed.insert(text)
        // The recently-used section may gain or lose rows; rebuild so anchors and
        // item counts stay in sync. Stable IDs preserve the user's scroll position.
        rebuildEntries()
    }

    fun getCategoryList(): List<PickerData.Category> = categories

    fun isHeader(position: Int): Boolean =
        position in entries.indices && entries[position].isHeader

    fun headerPositionOf(categoryIndex: Int): Int = anchors[categoryIndex] ?: 0

    fun sectionOf(position: Int): Int {
        var found = 0
        anchors.forEach { (index, anchor) ->
            if (anchor <= position) found = index
        }
        return found
    }

    fun updateStripWidth(view: View) {
        val width = view.width
        if (width <= 0 || width == lastStripWidth) return
        lastStripWidth = width
        cellSize = (width / density.columnCount).coerceAtLeast(view.context.dp(32))
        notifyItemRangeChanged(0, itemCount, SizePayload)
    }

    fun updateStripHeight(view: View) {
        val height = view.height
        if (height <= 0 || height == lastStripHeight) return
        lastStripHeight = height
        rowHeight = (height / density.rowCount).coerceAtLeast(view.context.dp(30))
        headerHeight = (rowHeight * 0.6f).roundToInt().coerceAtLeast(view.context.dp(26))
        notifyItemRangeChanged(0, itemCount, SizePayload)
    }

    override fun getItemCount() = entries.size

    override fun getItemId(position: Int): Long {
        val entry = entries.getOrNull(position) ?: return RecyclerView.NO_ID
        // Position keeps duplicates (same symbol used in several categories) unique.
        return entry.text.hashCode().toLong() * 31 + entry.categoryIndex.toLong() * 31 + position
    }

    override fun getItemViewType(position: Int) =
        if (isHeader(position)) ViewTypeHeader else ViewTypeItem

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == ViewTypeHeader) {
            val pad = parent.dp(10)
            val label = TextView(parent.context).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.keyTextColor.alpha(0.65f))
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(pad, parent.dp(5), pad, parent.dp(3))
                isClickable = false
            }
            HeaderViewHolder(label)
        } else {
            val keyView = TextKeyView(parent.context, theme, keyAppearance).apply {
                id = View.generateViewId()
                if (density.autoScale) {
                    mainText.apply {
                        scaleMode = AutoScaleTextView.Mode.Proportional
                        setPadding(hMargin, vMargin, hMargin, vMargin)
                    }
                }
            }
            applyFont(keyView)
            ItemViewHolder(keyView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> bindHeader(holder, position)
            is ItemViewHolder -> bindItem(holder, entries.getOrNull(position))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(SizePayload)) {
            when (holder) {
                is HeaderViewHolder -> bindHeader(holder, position)
                is ItemViewHolder -> applyItemSize(holder.keyView)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindHeader(holder: HeaderViewHolder, position: Int) {
        holder.textView.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            headerHeight
        )
        val index = entries.getOrNull(position)?.categoryIndex ?: return
        val category = categories.getOrNull(index) ?: return
        holder.textView.text = if (category.labelRes != 0) {
            holder.textView.context.getString(category.labelRes)
        } else {
            category.label
        }
    }

    private fun bindItem(holder: ItemViewHolder, entry: Entry?) {
        val keyView = holder.keyView
        applyFont(keyView)
        applyItemSize(keyView)
        // Leftover slot safety net: filtered data never yields empty text,
        // but stay blank and disabled if it somehow does.
        if (entry == null || entry.text.isEmpty()) {
            keyView.isEnabled = false
            keyView.mainText.text = ""
            keyView.setOnClickListener(null)
            keyView.setOnLongClickListener(null)
            keyView.swipeEnabled = false
            keyView.onGestureListener = null
            return
        }
        keyView.isEnabled = true
        keyView.mainText.text = entry.display
        keyView.setOnClickListener {
            keyActionListener.onKeyAction(CommitAction(entry.display), Source.Keyboard)
        }
        if (entry.categoryIndex == 0) {
            // 最近使用保持精简：无长按弹窗
            keyView.setOnLongClickListener(null)
            keyView.swipeEnabled = false
            keyView.onGestureListener = null
            return
        }
        keyView.setOnLongClickListener longClick@{ view ->
            if (view !is KeyView) return@longClick false
            val popup = policy.popup(entry.text) ?: return@longClick false
            view.updateBounds()
            popupActionListener.onPopupAction(
                PopupAction.ShowKeyboardAction(view.id, popup, view.bounds)
            )
            true
        }
        // RecyclerView owns vertical drags. Variants stay available by long press;
        // disabling per-key swipe prevents ACTION_CANCEL from being mistaken for a
        // popup selection while the symbol strip is scrolling.
        keyView.swipeEnabled = false
        keyView.onGestureListener = null
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is ItemViewHolder -> {
                holder.keyView.setOnClickListener(null)
                holder.keyView.setOnLongClickListener(null)
                holder.keyView.onGestureListener = null
                holder.keyView.swipeEnabled = false
            }
            is HeaderViewHolder -> {}
        }
    }

    private fun applyFont(keyView: TextKeyView) {
        if (FontProviders.needsRefresh()) {
            cachedTextSize = FontProviders.getFontSize("key_main_font", density.textSize)
            cachedTypeface = FontProviders.resolveTypeface("key_main_font", cachedTypeface)
        }
        keyView.mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedTextSize)
        if (keyView.mainText.typeface !== cachedTypeface) keyView.mainText.typeface = cachedTypeface
    }

    private fun applyItemSize(keyView: TextKeyView) {
        if (cellSize <= 0 || rowHeight <= 0) return
        val params = keyView.layoutParams
        if (params !is RecyclerView.LayoutParams || params.width != cellSize || params.height != rowHeight) {
            keyView.layoutParams = RecyclerView.LayoutParams(cellSize, rowHeight)
        }
    }

    companion object {
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
        private const val ViewTypeItem = 0
        private const val ViewTypeHeader = 1
        private val SizePayload = Any()
    }
}
