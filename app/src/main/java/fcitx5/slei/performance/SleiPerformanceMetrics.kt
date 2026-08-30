/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.performance

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object SleiPerformanceMetrics {
    private val serviceCreateMs = AtomicLong(-1)
    private val inputViewCreateMs = AtomicLong(-1)
    private val replaceInputViewMs = AtomicLong(-1)
    private val inputRequestedAt = AtomicLong(-1)
    private val inputRequestToWindowMs = AtomicLong(-1)

    fun recordServiceCreate(durationMs: Long) = serviceCreateMs.set(durationMs)
    fun recordInputViewCreate(durationMs: Long) = inputViewCreateMs.set(durationMs)
    fun recordReplaceInputView(durationMs: Long) = replaceInputViewMs.set(durationMs)
    fun markInputRequested() = inputRequestedAt.set(SystemClock.elapsedRealtime())
    fun markWindowShown() {
        val start = inputRequestedAt.getAndSet(-1)
        if (start > 0) inputRequestToWindowMs.set((SystemClock.elapsedRealtime() - start).coerceAtLeast(0))
    }

    fun summary(): String = buildString {
        append("最近一次弹出：").append(format(inputRequestToWindowMs.get())).append('\n')
        append("输入视图创建：").append(format(inputViewCreateMs.get())).append('\n')
        append("视图替换：").append(format(replaceInputViewMs.get())).append('\n')
        append("输入法服务启动：").append(format(serviceCreateMs.get()))
    }

    private fun format(value: Long) = if (value < 0) "暂无" else "${value} ms"
}
