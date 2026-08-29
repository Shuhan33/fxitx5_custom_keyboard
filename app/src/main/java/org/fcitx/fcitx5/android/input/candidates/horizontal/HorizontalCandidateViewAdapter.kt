/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {

    // Cache candidate font and refresh only when font configuration changes.
    private var candFont: Typeface? = FontProviders.resolveTypeface("cand_font", null)

    private fun refreshCandidateFontIfNeeded(): Boolean {
        if (FontProviders.needsRefresh()) {
            candFont = FontProviders.resolveTypeface("cand_font", null)
            return true
        }
        return false
    }

    init {
        setHasStableIds(true)
    }

    var candidates: Array<CandidateWord> = arrayOf()
        private set

    var total = -1
        private set

    var activeIndex = -1
        private set

    var indexOffset = 0
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(
        data: Array<CandidateWord>,
        total: Int,
        activeIndex: Int = this.activeIndex,
        indexOffset: Int = this.indexOffset,
    ) {
        val fontChanged = refreshCandidateFontIfNeeded()
        val sameContent = this.total == total &&
            this.indexOffset == indexOffset &&
            this.candidates.contentEquals(data)
        if (!fontChanged && sameContent && this.activeIndex != activeIndex) {
            updateActiveIndex(activeIndex)
            return
        }
        if (
            !fontChanged &&
            sameContent &&
            this.activeIndex == activeIndex &&
            this.indexOffset == indexOffset
        ) {
            return
        }
        val previousCandidates = this.candidates
        this.candidates = data
        this.total = total
        this.activeIndex = activeIndex
        this.indexOffset = indexOffset
        if (fontChanged) {
            notifyDataSetChanged()
            return
        }
        val shared = minOf(previousCandidates.size, data.size)
        if (shared > 0) notifyItemRangeChanged(0, shared)
        when {
            data.size > previousCandidates.size ->
                notifyItemRangeInserted(previousCandidates.size, data.size - previousCandidates.size)
            data.size < previousCandidates.size ->
                notifyItemRangeRemoved(data.size, previousCandidates.size - data.size)
        }
    }

    fun updateActiveIndex(index: Int) {
        if (index == activeIndex) return
        val previous = activeIndex
        activeIndex = index
        if (previous in candidates.indices) {
            notifyItemChanged(previous)
        }
        if (activeIndex in candidates.indices) {
            notifyItemChanged(activeIndex)
        }
    }

    override fun getItemCount() = candidates.size

    override fun getItemId(position: Int) =
        candidates.getOrNull(position).hashCode().toLong() * 31L + position

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme, candFont)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = RecyclerView.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        refreshCandidateFontIfNeeded()
        holder.ui.applyConfiguredTypeface(candFont)
        holder.ui.setActive(position == activeIndex)
        holder.update(position + indexOffset, candidates[position])
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.clear()
    }

}
