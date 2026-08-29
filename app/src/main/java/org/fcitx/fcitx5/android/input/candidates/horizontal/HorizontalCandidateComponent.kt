/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false
    private var highlightMovedInCurrentComposition = false
    private var lastPagedCandidatesSnapshot: List<CandidateWord> = emptyList()
    private var lastPagedCursor = -1
    private var lastPagedHasPrev = false
    private var lastPagedData: PagedCandidateEvent.Data? = null
    private var pagedCandidateFlowActive = false
    private var lastPagedEventUptimeMs = 0L
    private var lastRenderedCandidatesSnapshot: List<CandidateWord> = emptyList()
    private var lastRenderedActiveIndex = Int.MIN_VALUE
    private var pendingLegacyCandidateUpdate: Runnable? = null
    private var prefetchInFlight = false
    private var prefetchExhaustedForSnapshot = false

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        // New input session should not inherit paged-candidate flow state from previous one.
        pagedCandidateFlowActive = false
        lastPagedEventUptimeMs = 0L
        lastPagedData = null
    }

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    companion object {
        const val HorizontalLimit = 10
    }

    private fun refreshExpanded(childCount: Int) {
        val total = adapter.total
        val loaded = adapter.candidates.size
        val allLoaded = loaded == 0 || (total >= 0 && total <= HorizontalLimit)
        _expandedCandidateOffset.tryEmit(loaded.coerceAtMost(HorizontalLimit).coerceAtLeast(0))
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to allLoaded
        )
    }

    private fun ensureActiveCandidateVisible(
        originalCandidates: Array<CandidateWord>,
        total: Int,
        activeIndex: Int,
    ) {
        if (activeIndex !in originalCandidates.indices) {
            return
        }
        layoutManager.scrollToPosition(activeIndex)
    }

    private fun prefetchMoreCandidates(current: Array<CandidateWord>, total: Int) {
        val loaded = current.size
        if (loaded == 0 || prefetchInFlight || prefetchExhaustedForSnapshot) return
        val want = HorizontalLimit
        if (loaded >= want) return
        prefetchInFlight = true
        val snapshot = current.toList()
        fcitx.launchOnReady { api ->
            val extra = api.getCandidates(0, want)
            view.post {
                prefetchInFlight = false
                if (extra.isEmpty() || extra.size <= snapshot.size) {
                    prefetchExhaustedForSnapshot = true
                    return@post
                }
                if (adapter.candidates.toList() != snapshot) return@post
                val resolvedTotal = if (total >= 0) total else extra.size
                val capped = extra.copyOfRange(0, extra.size.coerceAtMost(HorizontalLimit))
                adapter.updateCandidates(capped, resolvedTotal, adapter.activeIndex, 0)
                refreshExpanded(layoutManager.childCount)
            }
        }
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.minimumWidth = layoutMinWidth
                holder.itemView.setOnClickListener {
                    val idx = holder.idx
                    val total = adapter.total
                    if (idx < 0 || (total >= 0 && idx >= total)) {
                        return@setOnClickListener
                    }
                    fcitx.launchOnReady { it.select(idx) }
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: LinearLayoutManager by lazy {
        object : LinearLayoutManager(context, HORIZONTAL, false) {
            override fun canScrollHorizontally() = true
            override fun canScrollVertically() = false
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return false
                }
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lm = this@HorizontalCandidateComponent.layoutManager
                    val candAdapter = this@HorizontalCandidateComponent.adapter
                    refreshExpanded(lm.childCount)
                    val last = lm.findLastVisibleItemPosition()
                    if (last >= candAdapter.itemCount - 3) {
                        prefetchMoreCandidates(candAdapter.candidates, candAdapter.total)
                    }
                }
            })
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (pagedCandidateFlowActive && data.total == -1) {
            val now = SystemClock.uptimeMillis()
            // Keep preferring paged events only when they are still arriving.
            // If paged stream is stale (e.g. engine/plugin restarted), fallback to legacy list updates.
            if (now - lastPagedEventUptimeMs <= 500L) {
                pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
                pendingLegacyCandidateUpdate = null
                return
            }
            pagedCandidateFlowActive = false
            lastPagedData = null
            pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        }
        lastPagedData = null
        lastRenderedCandidatesSnapshot = emptyList()
        lastRenderedActiveIndex = Int.MIN_VALUE
        prefetchExhaustedForSnapshot = false
        val candidates = data.candidates
        val total = data.total
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = Runnable {
            pendingLegacyCandidateUpdate = null
            // CandidateListEvent doesn't provide cursor info.
            updateCandidates(candidates, total, -1)
        }.also(view::post)
    }

    override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) {
        pagedCandidateFlowActive = true
        lastPagedEventUptimeMs = SystemClock.uptimeMillis()
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = null
        if (data == lastPagedData) {
            return
        }
        lastPagedData = data
        val candidates = data.candidates
        val cursorIndex = data.cursorIndex
        val normalizedCursor = when {
            cursorIndex in candidates.indices -> cursorIndex
            // Some engines may expose a global cursor index in paged mode.
            cursorIndex >= 0 && candidates.isNotEmpty() -> cursorIndex % candidates.size
            else -> -1
        }

        val isNewFirstPageSnapshot =
            !data.hasPrev && candidates.asList() != lastPagedCandidatesSnapshot
        if (isNewFirstPageSnapshot) {
            // New composing snapshot on the first page; keep it unhighlighted until moved.
            highlightMovedInCurrentComposition = false
            // Reset movement baseline for the new snapshot to avoid false positive move detection.
            lastPagedCursor = normalizedCursor
            lastPagedHasPrev = data.hasPrev
        }

        if (!highlightMovedInCurrentComposition && !isNewFirstPageSnapshot) {
            val cursorChanged = lastPagedCursor >= 0 && normalizedCursor >= 0 && normalizedCursor != lastPagedCursor
            val pageChanged = data.hasPrev != lastPagedHasPrev
            if (cursorChanged || pageChanged) {
                highlightMovedInCurrentComposition = true
            }
        }

        val effectiveActiveIndex = if (!highlightMovedInCurrentComposition && normalizedCursor == 0) {
            -1
        } else {
            normalizedCursor
        }

        val renderedCandidates = candidates.asList()
        if (
            renderedCandidates == lastRenderedCandidatesSnapshot &&
            effectiveActiveIndex == lastRenderedActiveIndex
        ) {
            return
        }

        lastPagedCandidatesSnapshot = candidates.asList()
        lastPagedCursor = normalizedCursor
        lastPagedHasPrev = data.hasPrev
        lastRenderedCandidatesSnapshot = renderedCandidates
        lastRenderedActiveIndex = effectiveActiveIndex

        updateCandidates(candidates, -1, effectiveActiveIndex)
    }

    private fun updateCandidates(
        candidates: Array<CandidateWord>,
        total: Int,
        activeIndex: Int,
    ) {
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
        val capped = if (candidates.size > HorizontalLimit) {
            candidates.copyOfRange(0, HorizontalLimit)
        } else {
            candidates
        }
        val cappedActive = if (activeIndex >= HorizontalLimit) -1 else activeIndex
        adapter.updateCandidates(capped, total, cappedActive, 0)
        prefetchExhaustedForSnapshot = false
        view.post {
            ensureActiveCandidateVisible(candidates, total, activeIndex)
            refreshExpanded(layoutManager.childCount)
        }
        prefetchMoreCandidates(candidates, total)
        // not sure why empty candidates won't trigger layout completion
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }
}
