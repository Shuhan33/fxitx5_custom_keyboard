/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.performance

import android.os.Build
import android.os.SystemClock
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object SleiPerformanceMetrics {
    private class PopupSample(
        val wallClockMs: Long,
        val durationMs: Long,
        val usedPreparedViews: Boolean
    )

    private const val MAX_POPUP_HISTORY = 12
    private val popups = ArrayDeque<PopupSample>(MAX_POPUP_HISTORY)
    private val popupTotal = AtomicLong(0)

    private val serviceCreateMs = AtomicLong(-1)
    private val inputViewCreateMs = AtomicLong(-1)
    private val inputViewConstructMs = AtomicLong(-1)
    private val inputViewAttachMs = AtomicLong(-1)
    private val replaceInputViewMs = AtomicLong(-1)
    private val viewPrewarmMs = AtomicLong(-1)
    private var lastConstructUsedPrepared = false
    private val inputRequestedAt = AtomicLong(-1)
    private val inputRequestToWindowMs = AtomicLong(-1)
    private val startInputPrepMs = AtomicLong(-1)
    private val deferredStartupMs = AtomicLong(-1)
    private val windowShownOverheadMs = AtomicLong(-1)

    // ---- InputView construction segments (diagnostics for the cold build cost) ----
    // buildMarks hold cumulative elapsed-since-start values; per-segment durations are
    // the deltas between consecutive marks, rendered lazily in report().
    private var buildStartAt = -1L
    private var buildWallClock = 0L
    private val buildMarks = mutableListOf<Pair<String, Long>>()
    private val buildExtras = mutableListOf<String>()

    /** Snapshot the start of an InputView construction; resets prior segments. */
    fun beginBuild() {
        buildStartAt = SystemClock.elapsedRealtime()
        buildWallClock = System.currentTimeMillis()
        buildMarks.clear()
        buildExtras.clear()
    }

    /** Record a timeline mark inside the construction flow (cumulative position). */
    fun markBuild(name: String) {
        if (buildStartAt < 0) return
        buildMarks.add(name to (SystemClock.elapsedRealtime() - buildStartAt))
    }

    /** Measure a self-contained construction step; emits an independent entry. */
    fun <T> timeBuild(name: String, block: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        val result = block()
        val duration = SystemClock.elapsedRealtime() - startedAt
        if (buildStartAt >= 0) buildExtras.add("$name=${duration}ms")
        return result
    }

    fun addBuildExtra(name: String, durationMs: Long) {
        if (buildStartAt >= 0) buildExtras.add("$name=${durationMs}ms")
    }

    private fun buildSegmentsString(): String {
        if (buildMarks.isEmpty()) return ""
        val out = StringBuilder()
        var previous = 0L
        buildMarks.forEachIndexed { index, (name, cumulative) ->
            if (index > 0) out.append(" / ")
            out.append(name).append('=').append(cumulative - previous).append("ms")
            previous = cumulative
        }
        return out.toString()
    }

    fun recordServiceCreate(durationMs: Long) {
        serviceCreateMs.set(durationMs)
        persistSummary()
    }

    fun recordInputViewCreate(durationMs: Long) {
        inputViewCreateMs.set(durationMs)
    }

    fun recordInputViewConstruct(durationMs: Long, usedPrepared: Boolean) {
        inputViewConstructMs.set(durationMs)
        lastConstructUsedPrepared = usedPrepared
    }

    fun recordInputViewAttach(durationMs: Long) {
        inputViewAttachMs.set(durationMs)
        persistSummary()
    }

    fun recordReplaceInputView(durationMs: Long) {
        replaceInputViewMs.set(durationMs)
    }

    fun recordViewPrewarm(durationMs: Long) {
        viewPrewarmMs.set(durationMs)
        persistSummary()
    }

    fun markInputRequested() {
        inputRequestedAt.set(SystemClock.elapsedRealtime())
    }

    /** onStartInputView synchronous preparation (caps reset, jobs, view sync). */
    fun recordStartInputPrep(durationMs: Long) {
        startInputPrepMs.set(durationMs)
        persistSummary()
    }

    /** Work running right after the first popup frame (deferred startup). */
    fun recordDeferredStartup(durationMs: Long) {
        deferredStartupMs.set(durationMs)
    }

    /** Work measured within [onWindowShown] (theme attr + aprefs sync). */
    fun recordWindowShownOverhead(durationMs: Long) {
        windowShownOverheadMs.set(durationMs)
    }

    fun markWindowShown() {
        val start = inputRequestedAt.getAndSet(-1)
        if (start < 0) return
        val duration = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
        inputRequestToWindowMs.set(duration)
        synchronized(popups) {
            popups.addLast(PopupSample(System.currentTimeMillis(), duration, lastConstructUsedPrepared))
            while (popups.size > MAX_POPUP_HISTORY) popups.removeFirst()
        }
        popupTotal.incrementAndGet()
        persistSummary()
    }

    fun summary(): String {
        if (hasData()) return liveSummary()
        // Fresh process without measurements yet: show the persisted values from the
        // previous session so the first-popup cold numbers are not silently lost.
        val persisted = runCatching {
            AppPrefs.getInstance().internal.lastPerformanceSummary.getValue()
        }.getOrDefault("")
        return persisted.ifEmpty { liveSummary() }
    }

    /**
     * Full multi-line diagnostic report with app/device context, intended for
     * copy-to-clipboard export from the data settings page.
     */
    fun report(): String = buildString {
        append("== slei 键盘性能诊断 ==\n")
        append("版本：").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("，Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("时间：").append(now()).append('\n')
        val count = popupTotal.get()
        append("累计弹出次数：").append(count).append('\n')
        if (count > 0) {
            append("最近一次弹出：").append(inputRequestToWindowMs.get()).append(" ms\n")
        }
        append("输入视图构建：").append(inputViewConstructMs.get()).append(" ms")
        if (lastConstructUsedPrepared) append("（复用预热视图）")
        append('\n')
        val segments = buildSegmentsString()
        if (segments.isNotEmpty()) {
            append("最近一次视图构建（").append(timestamp(buildWallClock)).append("）分段：")
                .append(segments).append('\n')
        }
        if (buildExtras.isNotEmpty()) {
            append("构建明细（同一次构建）：").append(buildExtras.take(12).joinToString(" ")).append('\n')
        }
        append("输入视图挂载：").append(inputViewAttachMs.get()).append(" ms\n")
        append("onCreateInputView 总耗时：").append(inputViewCreateMs.get()).append(" ms\n")
        append("空闲预热：").append(viewPrewarmMs.get()).append(" ms\n")
        append("输入法服务启动：").append(serviceCreateMs.get()).append(" ms\n")
        append("会话准备（startInput）：").append(startInputPrepMs.get()).append(" ms\n")
        append("首帧后工作（sync/voice）：").append(deferredStartupMs.get()).append(" ms\n")
        append("窗口显示开销：").append(windowShownOverheadMs.get()).append(" ms\n")
        synchronized(popups) {
            if (popups.isNotEmpty()) {
                append("-- 最近弹出样本 --\n")
                var index = popups.size
                popups.reversed().forEach { sample ->
                    append("${index--}) ").append(timestamp(sample.wallClockMs)).append("  ")
                        .append(sample.durationMs).append(" ms  ")
                        .append(if (sample.usedPreparedViews) "复用预热视图" else "现场构建")
                        .append('\n')
                }
            }
        }
    }

    private fun liveSummary(): String = buildString {
        val count = popupTotal.get()
        if (count > 0) {
            append("最近弹出：${inputRequestToWindowMs.get()} ms")
            append("（第 $count 次 · ${describePrepared()}")
            val attach = inputViewAttachMs.get()
            if (attach >= 0) append(" · 挂载 $attach ms")
            append("）\n")
        } else {
            append("最近弹出：暂无\n")
        }
        append("输入视图构建：${format(inputViewConstructMs.get())}${describePreparedSuffix()}")
        append('\n')
        append("输入视图挂载：${format(inputViewAttachMs.get())}\n")
        append("视图替换（onCreateInputView）：${format(inputViewCreateMs.get())}\n")
        append("空闲预热：${format(viewPrewarmMs.get())}\n")
        append("输入法服务启动：${format(serviceCreateMs.get())}\n")
        append("会话准备（startInput）：${format(startInputPrepMs.get())}\n")
        append("首帧后工作（sync/voice）：${format(deferredStartupMs.get())}\n")
        append("窗口显示开销：${format(windowShownOverheadMs.get())}\n")
        synchronized(popups) {
            if (popups.isNotEmpty()) {
                append("最近弹出样本：").append(
                    popups.reversed().take(5)
                        .joinToString(", ") { "${it.durationMs}ms${if (it.usedPreparedViews) "(复用)" else "(现场)"}" }
                ).append('\n')
            }
        }
    }

    private fun describePrepared() =
        if (lastConstructUsedPrepared) "复用预热" else "现场构建"

    private fun describePreparedSuffix() =
        if (inputViewConstructMs.get() >= 0) if (lastConstructUsedPrepared) "（复用预热）" else "（现场构建）" else ""

    private fun hasData(): Boolean =
        popupTotal.get() > 0 || inputViewConstructMs.get() >= 0 || viewPrewarmMs.get() >= 0

    private fun persistSummary() {
        runCatching {
            AppPrefs.getInstance().internal.lastPerformanceSummary.setValue(liveSummary())
        }
    }

    private fun format(value: Long) = if (value < 0) "暂无" else if (value > 2000) "${value / 1000.0} s" else "$value ms"

    private fun now() = timestamp(System.currentTimeMillis())

    private fun timestamp(wallClockMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(wallClockMs))
}
