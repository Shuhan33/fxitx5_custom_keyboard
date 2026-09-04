/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.stats

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val formatVersion: Int = 3,
    val totalCharacters: Long = 0,
    val totalCommits: Long = 0,
    val phraseFrequency: Map<String, Long> = emptyMap(),
    val dailyCharacters: Map<String, Long> = emptyMap(),
    val weeklyPhraseFrequency: Map<String, Map<String, Long>> = emptyMap(),
    val hiddenPhrases: Set<String> = emptySet(),
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
    @Volatile
    private var collectionEnabled = true
    private var data = InputStatsData()
    private var flushJob: Job? = null

    fun init(context: Context) {
        if (::applicationContext.isInitialized) return
        applicationContext = context.applicationContext
        collectionEnabled = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(PREF_COLLECTION_ENABLED, true)
        scope.launch { mutex.withLock { ensureLoadedLocked() } }
    }

    fun isCollectionEnabled(): Boolean = collectionEnabled

    fun setCollectionEnabled(enabled: Boolean) {
        collectionEnabled = enabled
        if (::applicationContext.isInitialized) {
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit().putBoolean(PREF_COLLECTION_ENABLED, enabled).apply()
        }
    }

    fun recordCommit(text: String, sensitive: Boolean) {
        if (
            !collectionEnabled || sensitive || text.isBlank() ||
            !::applicationContext.isInitialized
        ) return
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
                if (phrase != null && phrase !in data.hiddenPhrases) {
                    phrases[phrase] = (phrases[phrase] ?: 0L) + 1L
                }
                if (phrases.size > MAX_PHRASES) {
                    phrases.entries.sortedByDescending { it.value }
                        .drop(MAX_PHRASES_TO_KEEP)
                        .forEach { phrases.remove(it.key) }
                }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                val currentWeek = weekStartKey()
                val daily = data.dailyCharacters.toMutableMap()
                daily[today] = (daily[today] ?: 0L) + characterCount
                val weekly = data.weeklyPhraseFrequency
                    .mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableMap() }
                if (phrase != null && phrase !in data.hiddenPhrases) {
                    val weekPhrases = weekly.getOrPut(currentWeek) { mutableMapOf() }
                    weekPhrases[phrase] = (weekPhrases[phrase] ?: 0L) + 1L
                }
                data = data.copy(
                    totalCharacters = data.totalCharacters + characterCount,
                    totalCommits = data.totalCommits + 1L,
                    phraseFrequency = phrases,
                    dailyCharacters = daily,
                    weeklyPhraseFrequency = weekly,
                    updatedAt = System.currentTimeMillis()
                )
                pruneWeeklyDetailsLocked()
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
                val weekly = data.weeklyPhraseFrequency
                    .mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableMap() }
                imported.weeklyPhraseFrequency.forEach { (week, values) ->
                    val target = weekly.getOrPut(week) { mutableMapOf() }
                    values.forEach { (phrase, value) -> target[phrase] = (target[phrase] ?: 0L) + value }
                }
                val hidden = data.hiddenPhrases + imported.hiddenPhrases
                InputStatsData(
                    totalCharacters = data.totalCharacters + imported.totalCharacters,
                    totalCommits = data.totalCommits + imported.totalCommits,
                    phraseFrequency = phrases.filterKeys { it !in hidden }.entries
                        .sortedByDescending { it.value }.take(MAX_PHRASES_TO_KEEP)
                        .associate { it.key to it.value },
                    dailyCharacters = daily,
                    weeklyPhraseFrequency = weekly.mapValues { (_, values) ->
                        values.filterKeys { it !in hidden }
                    },
                    hiddenPhrases = hidden
                )
            }
            pruneWeeklyDetailsLocked()
            writeLocked()
        }
    }

    suspend fun clear(mode: ClearMode) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            data = when (mode) {
                ClearMode.Counters -> data.copy(
                    totalCharacters = 0,
                    totalCommits = 0,
                    dailyCharacters = emptyMap()
                )
                ClearMode.Phrases -> data.copy(
                    phraseFrequency = emptyMap(),
                    weeklyPhraseFrequency = emptyMap(),
                    hiddenPhrases = emptySet()
                )
                ClearMode.All -> InputStatsData()
            }.copy(updatedAt = System.currentTimeMillis())
            writeLocked()
        }
    }

    /** Permanently omit a phrase from overall and weekly rankings. */
    suspend fun hidePhrase(phrase: String) = withContext(Dispatchers.IO) {
        val normalized = phrase.trim()
        if (normalized.isEmpty()) return@withContext
        mutex.withLock {
            ensureLoadedLocked()
            data = data.copy(
                phraseFrequency = data.phraseFrequency - normalized,
                weeklyPhraseFrequency = data.weeklyPhraseFrequency.mapValues { (_, phrases) ->
                    phrases - normalized
                },
                hiddenPhrases = data.hiddenPhrases + normalized,
                updatedAt = System.currentTimeMillis()
            )
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
        pruneWeeklyDetailsLocked()
        loaded = true
    }

    private fun pruneWeeklyDetailsLocked() {
        val retainedWeeks = (0 until RETAINED_WEEK_COUNT).map(::weekStartKey).toSet()
        val oldestDay = retainedWeeks.minOrNull().orEmpty()
        data = data.copy(
            formatVersion = 3,
            phraseFrequency = data.phraseFrequency.filterKeys { it !in data.hiddenPhrases },
            dailyCharacters = data.dailyCharacters.filterKeys { it >= oldestDay },
            weeklyPhraseFrequency = data.weeklyPhraseFrequency
                .filterKeys { it in retainedWeeks }
                .mapValues { (_, phrases) ->
                    phrases.filterKeys { it !in data.hiddenPhrases }.entries
                        .sortedByDescending { it.value }.take(MAX_WEEKLY_PHRASES)
                        .associate { it.key to it.value }
                }
        )
    }

    fun weekStartKey(weeksAgo: Int = 0): String {
        val calendar = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            val daysSinceMonday = (get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
            add(java.util.Calendar.DAY_OF_YEAR, -daysSinceMonday - weeksAgo * 7)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.time)
    }

    private fun scheduleFlushLocked() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            mutex.withLock {
                writeLocked()
                flushJob = null
            }
        }
    }

    /** Persist pending counters when an editor session finishes. */
    fun flush() {
        if (!::applicationContext.isInitialized) return
        scope.launch {
            mutex.withLock {
                flushJob?.cancel()
                flushJob = null
                if (loaded) writeLocked()
            }
        }
    }

    /** Final synchronous flush for service teardown, where an async job may be cancelled by process death. */
    fun flushBlocking() {
        if (!::applicationContext.isInitialized) return
        runBlocking(Dispatchers.IO) {
            mutex.withLock {
                flushJob?.cancel()
                flushJob = null
                if (loaded) writeLocked()
            }
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
    private const val MAX_WEEKLY_PHRASES = 300
    private const val PREF_COLLECTION_ENABLED = "slei_stats_collection_enabled"
    const val RETAINED_WEEK_COUNT = 3
}
