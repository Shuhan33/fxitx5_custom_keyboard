/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.performance

import android.os.Build
import android.os.Process
import android.os.SystemClock
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
 *
 * The same trail is appended to the performance report, so the capacity must
 * cover several popups' worth of events (each popup logs ~5 lines).
 */
object SleiLifecycleBreadcrumbs {
    private const val MAX_ENTRIES = 256
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

    /**
     * Milliseconds since this process was started, or -1 when unavailable (API < 24).
     * A small value on a service create/popup means the process itself was just cold-started.
     * Reads a cached static value, so it is safe on the main thread.
     */
    fun processUptimeMs(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return -1L
        return try {
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        } catch (_: Throwable) {
            -1L
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
