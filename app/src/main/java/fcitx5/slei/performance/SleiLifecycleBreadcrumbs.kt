/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.performance

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-memory lifecycle breadcrumb trail for crash forensics.
 *
 * The IME's reported "random" failures (theme switch, app switch, keyboard
 * hide) need context after the fact. Every significant lifecycle transition
 * records one line here, and [FcitxApplication.persistCrashReport] appends the
 * snapshot to every saved crash report, so the reproducible scenario is
 * captured without relying on the user's memory of exact steps.
 */
object SleiLifecycleBreadcrumbs {
    private const val MAX_ENTRIES = 80
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val lock = Any()
    private val timeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** Record one lifecycle event. Must not throw; called from any thread. */
    fun mark(event: String) {
        try {
            val line = "${timeFormatter.format(Instant.now())} $event"
            synchronized(lock) {
                entries.addLast(line)
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
        } catch (_: Throwable) {
        }
    }

    fun markCrash(throwable: Throwable) {
        mark(
            "!!!! CRASH: ${throwable.javaClass.name}: " +
                (throwable.message ?: "").take(160)
        )
    }

    /** 最近事件，老->新顺序多行文本。 */
    fun snapshot(): String {
        synchronized(lock) {
            if (entries.isEmpty()) return "(no lifecycle breadcrumbs recorded)"
            return entries.joinToString("\n")
        }
    }
}
