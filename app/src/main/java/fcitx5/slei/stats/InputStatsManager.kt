/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.stats

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class InputStatsData(
    val formatVersion: Int = 1,
    val totalCharacters: Long = 0,
    val totalCommits: Long = 0,
    val phraseFrequency: Map<String, Long> = emptyMap(),
    val dailyCharacters: Map<String, Long> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
)

object InputStatsManager {
    enum class ClearMode { Counters, Phrases, All }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var applicationContext: Context
    private var loaded = false
    private var data = InputStatsData()
    private var flushJob: Job? = null

    fun init(context: Context) {
        if (::applicationContext.isInitialized) return
        applicationContext = context.applicationContext
        scope.launch { mutex.withLock { ensureLoadedLocked() } }
    }

    fun recordCommit(text: String, sensitive: Boolean) {
        if (sensitive || text.isBlank() || !::applicationContext.isInitialized) return
        scope.launch {
            mutex.withLock {
                ensureLoadedLocked()
                val characterCount = text.codePoints()
                    .filter { Character.isLetterOrDigit(it) }
                    .count()
                if (characterCount == 0L) return@withLock

                val phrase = text.trim()
                    .replace(Regex("\\s+"), " ")
                    .takeIf { it.length in 2..40 && it.any(Char::isLetterOrDigit) }
                val phrases = data.phraseFrequency.toMutableMap()
                if (phrase != null) phrases[phrase] = (phrases[phrase] ?: 0L) + 1L
                if (phrases.size > MAX_PHRASES) {
                    phrases.entries.sortedByDescending { it.value }
                        .drop(MAX_PHRASES_TO_KEEP)
                        .forEach { phrases.remove(it.key) }
                }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                val daily = data.dailyCharacters.toMutableMap()
                daily[today] = (daily[today] ?: 0L) + characterCount
                data = data.copy(
                    totalCharacters = data.totalCharacters + characterCount,
                    totalCommits = data.totalCommits + 1L,
                    phraseFrequency = phrases,
                    dailyCharacters = daily.entries.sortedByDescending { it.key }.take(400)
                        .associate { it.key to it.value },
                    updatedAt = System.currentTimeMillis()
                )
                scheduleFlushLocked()
            }
        }
    }

    suspend fun snapshot(): InputStatsData = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            data.copy(
                phraseFrequency = data.phraseFrequency.toMap(),
                dailyCharacters = data.dailyCharacters.toMap()
            )
        }
    }

    suspend fun exportTo(output: OutputStream) = withContext(Dispatchers.IO) {
        output.writer(Charsets.UTF_8).use { it.write(json.encodeToString(snapshot())) }
    }

    suspend fun importFrom(input: InputStream, merge: Boolean) = withContext(Dispatchers.IO) {
        val imported = input.reader(Charsets.UTF_8).use { json.decodeFromString<InputStatsData>(it.readText()) }
        mutex.withLock {
            ensureLoadedLocked()
            data = if (!merge) {
                imported.copy(updatedAt = System.currentTimeMillis())
            } else {
                val phrases = data.phraseFrequency.toMutableMap()
                imported.phraseFrequency.forEach { (key, value) -> phrases[key] = (phrases[key] ?: 0L) + value }
                val daily = data.dailyCharacters.toMutableMap()
                imported.dailyCharacters.forEach { (key, value) -> daily[key] = (daily[key] ?: 0L) + value }
                InputStatsData(
                    totalCharacters = data.totalCharacters + imported.totalCharacters,
                    totalCommits = data.totalCommits + imported.totalCommits,
                    phraseFrequency = phrases.entries.sortedByDescending { it.value }.take(MAX_PHRASES_TO_KEEP)
                        .associate { it.key to it.value },
                    dailyCharacters = daily.entries.sortedByDescending { it.key }.take(400)
                        .associate { it.key to it.value }
                )
            }
            writeLocked()
        }
    }

    suspend fun clear(mode: ClearMode) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            data = when (mode) {
                ClearMode.Counters -> data.copy(totalCharacters = 0, totalCommits = 0, dailyCharacters = emptyMap())
                ClearMode.Phrases -> data.copy(phraseFrequency = emptyMap())
                ClearMode.All -> InputStatsData()
            }.copy(updatedAt = System.currentTimeMillis())
            writeLocked()
        }
    }

    private fun statsFile() = applicationContext.filesDir.resolve("slei/input_stats.json")

    private fun ensureLoadedLocked() {
        if (loaded) return
        data = runCatching {
            val file = statsFile()
            if (file.exists()) json.decodeFromString<InputStatsData>(file.readText()) else InputStatsData()
        }.getOrDefault(InputStatsData())
        loaded = true
    }

    private fun scheduleFlushLocked() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            mutex.withLock { writeLocked() }
        }
    }

    private fun writeLocked() {
        val file = statsFile()
        file.parentFile?.mkdirs()
        val temp = file.resolveSibling("${file.name}.tmp")
        temp.writeText(json.encodeToString(data))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private const val FLUSH_DELAY_MS = 2_000L
    private const val MAX_PHRASES = 3_500
    private const val MAX_PHRASES_TO_KEEP = 3_000
}
