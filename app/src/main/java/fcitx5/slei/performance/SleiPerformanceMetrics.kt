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
        append("输入视图挂载：").append(inputViewAttachMs.get()).append(" ms\n")
        append("onCreateInputView 总耗时：").append(inputViewCreateMs.get()).append(" ms\n")
        append("空闲预热：").append(viewPrewarmMs.get()).append(" ms\n")
        append("输入法服务启动：").append(serviceCreateMs.get()).append(" ms\n")
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
