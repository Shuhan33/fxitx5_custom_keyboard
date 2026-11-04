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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object SleiPerformanceMetrics {
    /** Where the InputView shown for a popup came from. */
    private enum class ViewSource(val label: String, val shortLabel: String) {
        COLD("现场构建", "现场"),
        PREWARM("预热视图", "预热"),
        REUSED("复用已有视图", "复用")
    }

    private class PopupSample(
        val wallClockMs: Long,
        val durationMs: Long,
        val serviceOrdinal: Int,
        val viewSource: ViewSource
    )

    private const val MAX_POPUP_HISTORY = 12
    private val popups = ArrayDeque<PopupSample>(MAX_POPUP_HISTORY)
    private val popupTotal = AtomicLong(0)
    // Bumped by every InputView attach (see recordInputViewConstruct). A popup whose window
    // shows with no attach since the previous popup reused the view attached before it.
    // Accessed on the main thread only.
    private var viewBuildSeq = 0
    private var popupSeenBuildSeq = 0

    private val serviceCreateMs = AtomicLong(-1)
    // Service instance lifecycle breadcrumbs: system can destroy and recreate the
    // InputMethodService instance (process surviving) while the keyboard is hidden;
    // every such recreation loses the prepared views and forces a full cold rebuild.
    private val serviceCreateCount = AtomicInteger(0)
    private val lastServiceDestroyWallClock = AtomicLong(-1)
    private val themeRebuildCount = AtomicInteger(0)
    private val lastThemeRebuildWallClock = AtomicLong(-1)
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

    // ---- BaseKeyboard.reloadLayout() segment probe ----
    // A probe is open only between beginReloadLayout() and endReloadLayout(). Every hook reads
    // the nullable field first, so outside a reload a hook costs one null check; KeyView
    // internals are timed only while a reload is in progress. Keyboards are created and
    // reloaded on the main thread, so the probe itself is not synchronized.
    private class ReloadLayoutProbe(
        val keyboard: String,
        val wallClockMs: Long,
        val startNs: Long
    ) {
        var reused = false
        var keyPhase = false
        var totalNs = 0L
        // Durations summed by segment name ("r:*" top level, "r:keys"/"r:rows" inside the
        // build, "k:*" per createKeyView step, "kv:*" inside KeyView construction).
        val segmentsNs = LinkedHashMap<String, Long>()
        val segmentCounts = HashMap<String, Int>()
        // Total createKeyView duration per key of the row build, in call order.
        val keyNs = ArrayList<Long>(64)
    }

    private const val MAX_RELOAD_HISTORY = 12
    private var reloadProbe: ReloadLayoutProbe? = null
    private val reloadHistory = ArrayDeque<ReloadLayoutProbe>(MAX_RELOAD_HISTORY)

    /** Opens the probe for one reloadLayout() call of [keyboard]. */
    fun beginReloadLayout(keyboard: String) {
        reloadProbe = ReloadLayoutProbe(
            keyboard, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()
        )
    }

    /** Start instant (ns) of a timed reload segment; 0 when no probe is open. */
    fun reloadStart(): Long = if (reloadProbe != null) SystemClock.elapsedRealtimeNanos() else 0L

    /** Start instant (ns) of a createKeyView step; 0 unless inside the row build phase. */
    fun reloadKeyStart(): Long {
        val probe = reloadProbe ?: return 0L
        return if (probe.keyPhase) SystemClock.elapsedRealtimeNanos() else 0L
    }

    /** Adds the time since [startNs] to the named segment (no-op when [startNs] is 0). */
    fun reloadEnd(name: String, startNs: Long) {
        if (startNs == 0L) return
        val probe = reloadProbe ?: return
        val duration = SystemClock.elapsedRealtimeNanos() - startNs
        probe.segmentsNs[name] = (probe.segmentsNs[name] ?: 0L) + duration
        probe.segmentCounts[name] = (probe.segmentCounts[name] ?: 0) + 1
    }

    /** Records one createKeyView total of the row build phase (no-op when [startNs] is 0). */
    fun reloadKeyEnd(startNs: Long) {
        if (startNs == 0L) return
        val probe = reloadProbe ?: return
        probe.keyNs.add(SystemClock.elapsedRealtimeNanos() - startNs)
    }

    fun setReloadKeyPhase(active: Boolean) {
        reloadProbe?.keyPhase = active
    }

    fun markReloadRows(reused: Boolean) {
        reloadProbe?.reused = reused
    }

    /** Times an expression-valued KeyView part (e.g. a property initializer) into the open probe. */
    inline fun <T> timeReload(name: String, block: () -> T): T {
        val startNs = reloadStart()
        val result = block()
        reloadEnd(name, startNs)
        return result
    }

    /** Closes the probe and keeps it in the bounded history used by the report. */
    fun endReloadLayout() {
        val probe = reloadProbe ?: return
        reloadProbe = null
        probe.totalNs = SystemClock.elapsedRealtimeNanos() - probe.startNs
        synchronized(reloadHistory) {
            reloadHistory.addLast(probe)
            while (reloadHistory.size > MAX_RELOAD_HISTORY) reloadHistory.removeFirst()
        }
    }

    private fun reloadLayoutReport(): String {
        val samples = synchronized(reloadHistory) { reloadHistory.toList() }
        if (samples.isEmpty()) return ""
        return buildString {
            append("键盘重排分段（BaseKeyboard.reloadLayout，最近 ${samples.size} 次，单位 ms）：\n")
            samples.forEach { appendReloadSample(it) }
        }
    }

    private fun StringBuilder.appendReloadSample(probe: ReloadLayoutProbe) {
        val segments = probe.segmentsNs
        fun ns(name: String): Long = segments[name] ?: 0L
        val topLevel = listOf("r:prep", "r:sig", "r:layout", "r:build", "r:aux", "r:place", "r:rest")
        val attributed = topLevel.sumOf { ns(it) }
        append(probe.keyboard).append(if (probe.reused) " 复用缓存" else " 现场构建")
            .append("（").append(timestamp(probe.wallClockMs)).append("）")
            .append(" 总计 ").append(msText(probe.totalNs))
            .append("，未归类 ").append(msText(probe.totalNs - attributed)).append('\n')
        append("  分段：")
        topLevel.forEach { append(it.removePrefix("r:")).append(' ').append(msText(ns(it))).append("  ") }
        append('\n')
        if (probe.reused) return
        val keys = probe.keyNs
        val restKeys = keys.drop(1).sorted()
        val median = when {
            restKeys.isEmpty() -> 0L
            restKeys.size % 2 == 1 -> restKeys[restKeys.size / 2]
            else -> (restKeys[restKeys.size / 2 - 1] + restKeys[restKeys.size / 2]) / 2
        }
        append("  按键 ").append(keys.size).append(" 个：首键 ").append(msText(keys.firstOrNull() ?: 0L))
            .append("，其余中位数 ").append(msText(median))
            .append("，最大 ").append(msText(keys.maxOrNull() ?: 0L)).append('\n')
        append("  build 内：keys ").append(msText(ns("r:keys")))
            .append("（resolve ").append(msText(ns("k:resolve")))
            .append(" / ctor ").append(msText(ns("k:ctor")))
            .append(" / apply ").append(msText(ns("k:apply")))
            .append(" / fonts ").append(msText(ns("k:fonts")))
            .append("），rows ").append(msText(ns("r:rows")))
            .append("，其它 ").append(msText(ns("r:build") - ns("r:keys") - ns("r:rows"))).append('\n')
        val viewParts = segments.filterKeys { it.startsWith("kv:") }
        if (viewParts.isNotEmpty()) {
            append("  KeyView 内（嵌套计入，可与上面重叠）：")
            viewParts.forEach { (name, value) ->
                append(name.removePrefix("kv:")).append(' ').append(msText(value))
                    .append('(').append(probe.segmentCounts[name] ?: 0).append("次)  ")
            }
            append('\n')
        }
    }

    private fun msText(ns: Long): String = String.format(Locale.ROOT, "%.1f", ns / 1_000_000.0)

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
        viewBuildSeq++
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

    /** A deferred pending-theme rebuild actually replaced the InputView(s). */
    fun markThemeRebuild() {
        themeRebuildCount.incrementAndGet()
        lastThemeRebuildWallClock.set(System.currentTimeMillis())
    }

    /**
     * Service instance created (onCreate). Returns this instance's 1-based ordinal within the
     * process, so breadcrumbs and popup samples can tell the instances apart.
     */
    fun markServiceCreate(): Int = serviceCreateCount.incrementAndGet()

    /** Service instance destroyed by the system (onDestroy); process may survive. */
    fun markServiceDestroy() {
        lastServiceDestroyWallClock.set(System.currentTimeMillis())
    }

    /** Window shown for a popup, served by the service instance with [serviceOrdinal]. */
    fun markWindowShown(serviceOrdinal: Int) {
        // Origin of this popup's view: an attach since the last popup means the view was built
        // then (prewarmed or cold); otherwise the view attached earlier is reused as is.
        val source = when {
            viewBuildSeq == popupSeenBuildSeq -> ViewSource.REUSED
            lastConstructUsedPrepared -> ViewSource.PREWARM
            else -> ViewSource.COLD
        }
        popupSeenBuildSeq = viewBuildSeq
        val start = inputRequestedAt.getAndSet(-1)
        if (start < 0) return
        val duration = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
        inputRequestToWindowMs.set(duration)
        synchronized(popups) {
            popups.addLast(PopupSample(System.currentTimeMillis(), duration, serviceOrdinal, source))
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
        append(reloadLayoutReport())
        append("输入视图挂载：").append(inputViewAttachMs.get()).append(" ms\n")
        append("onCreateInputView 总耗时：").append(inputViewCreateMs.get()).append(" ms\n")
        append("空闲预热：").append(viewPrewarmMs.get()).append(" ms\n")
        append("输入法服务启动：").append(serviceCreateMs.get()).append(" ms\n")
        append("输入法服务实例：").append(serviceCreateCount.get()).append(" 次")
        val destroyedAt = lastServiceDestroyWallClock.get()
        if (destroyedAt > 0) {
            append("（上次实例销毁：").append(timestamp(destroyedAt)).append("）")
        }
        append('\n')
        if (themeRebuildCount.get() > 0) {
            append("主题切换重建：").append(themeRebuildCount.get()).append(" 次")
                .append("（最近：").append(timestamp(lastThemeRebuildWallClock.get())).append("）\n")
        }
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
                        .append("实例#").append(sample.serviceOrdinal).append("  ")
                        .append(sample.viewSource.label)
                        .append('\n')
                }
            }
        }
        // Event trail (service/app/window/theme transitions) to attribute slow popups to a cause.
        append("-- 生命周期记录（HH:mm:ss.SSS，老→新）--\n")
        append(SleiLifecycleBreadcrumbs.snapshot()).append('\n')
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
        append("服务实例：${serviceCreateCount.get()} 次（主题切换重建 ${themeRebuildCount.get()} 次）\n")
        append("会话准备（startInput）：${format(startInputPrepMs.get())}\n")
        append("首帧后工作（sync/voice）：${format(deferredStartupMs.get())}\n")
        append("窗口显示开销：${format(windowShownOverheadMs.get())}\n")
        synchronized(popups) {
            if (popups.isNotEmpty()) {
                append("最近弹出样本：").append(
                    popups.reversed().take(5)
                        .joinToString(", ") { "${it.durationMs}ms(#${it.serviceOrdinal}·${it.viewSource.shortLabel})" }
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
