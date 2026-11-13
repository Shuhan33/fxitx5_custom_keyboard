/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import java.io.File

/**
 * Loads fontset metadata and custom typefaces on a background thread. UI-thread lookups only
 * read the last published immutable snapshot and fall back to the current/system face while a
 * new snapshot is loading.
 */
class DefaultFontProvider : FontProviderApi {
    private data class FontCache(
        val generation: Long,
        val loaded: Boolean,
        val typefaces: Map<String, Typeface?>,
        val sizes: Map<String, Float>
    )

    private data class LoadCallback(
        val onComplete: ((MutableMap<String, Typeface?>) -> Unit)?,
        val onFailure: (() -> Unit)?
    )

    private val cacheLock = Any()
    @Volatile
    private var cache = FontCache(0L, false, emptyMap(), emptyMap())
    private var isLoading = false
    private val loadCallbacks = mutableListOf<LoadCallback>()

    override fun clearCache() {
        synchronized(cacheLock) {
            cache = FontCache(cache.generation + 1, false, emptyMap(), emptyMap())
            // An active loader observes the new generation before publishing. It owns the
            // single in-flight slot and will reload the latest generation before completing.
        }
    }

    fun isCacheLoaded(): Boolean = cache.loaded

    /**
     * Load the current generation once. Repeated requests coalesce onto one worker; if the
     * fontset changes during I/O, that same worker discards its stale result and loads the
     * latest generation before notifying listeners.
     */
    fun preloadFontsAsync(
        onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        var readySnapshot: FontCache? = null
        var shouldStartWorker = false
        synchronized(cacheLock) {
            if (cache.loaded) {
                readySnapshot = cache
            } else {
                if (onComplete != null || onFailure != null) {
                    loadCallbacks += LoadCallback(onComplete, onFailure)
                }
                if (!isLoading) {
                    isLoading = true
                    shouldStartWorker = true
                }
            }
        }
        readySnapshot?.let {
            onComplete?.invoke(it.typefaces.toMutableMap())
            return
        }
        if (shouldStartWorker) {
            Thread({ loadLatestGeneration() }, "FcitxFontPreload").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun loadLatestGeneration() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            val generation = cache.generation
            val startedAt = SystemClock.elapsedRealtime()
            val loaded = runCatching { loadFontCache(generation) }.getOrNull()
            if (loaded == null) {
                val callbacks: List<LoadCallback>
                synchronized(cacheLock) {
                    isLoading = false
                    callbacks = loadCallbacks.toList()
                    loadCallbacks.clear()
                }
                Log.w("FcitxColdStart", "font preload failed; will retry on next lookup")
                callbacks.forEach { callback -> runCatching { callback.onFailure?.invoke() } }
                return
            }

            val callbacks: List<LoadCallback>?
            synchronized(cacheLock) {
                if (cache.generation != generation) {
                    callbacks = null
                } else {
                    cache = loaded
                    isLoading = false
                    callbacks = loadCallbacks.toList()
                    loadCallbacks.clear()
                }
            }
            if (callbacks == null) continue

            Log.i(
                "FcitxColdStart",
                "font preload keys=${loaded.typefaces.size} " +
                    "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            val published = loaded
            callbacks.forEach { callback ->
                runCatching {
                    callback.onComplete?.invoke(published.typefaces.toMutableMap())
                }
            }
            return
        }
    }

    private fun loadFontCache(generation: Long): FontCache? {
        val result = ConfigProviders.readFontsetPathMapSnapshot()
        val snapshot = result.getOrNull()
        if (snapshot == null) {
            // A confirmed absent fontset is a valid empty configuration. Parse or path errors
            // remain retryable instead of being cached as a permanent absence.
            if (result.isSuccess && UserConfigFiles.fontsetJson() != null) {
                return FontCache(generation, true, emptyMap(), emptyMap())
            }
            return if (result.isSuccess) {
                FontCache(generation, true, emptyMap(), emptyMap())
            } else {
                null
            }
        }
        val fontsDir = snapshot.file?.parentFile ?: return null
        val typefaces = mutableMapOf<String, Typeface?>()
        val typefacesByPaths = mutableMapOf<List<String>, Typeface?>()
        val fontKeys = snapshot.value.keys.filterNot { it.endsWith("_size") }
        fontKeys.forEach { key ->
            val paths = snapshot.value[key].orEmpty()
                .map { it.trim() }
                .map { File(fontsDir, it) }
                .filter(File::exists)
                .map(File::getAbsolutePath)
            val typeface = if (typefacesByPaths.containsKey(paths)) {
                typefacesByPaths[paths]
            } else {
                val startedAt = SystemClock.elapsedRealtime()
                val loaded = runCatching {
                    when {
                        paths.isEmpty() -> null
                        paths.size == 1 || android.os.Build.VERSION.SDK_INT < 29 ->
                            Typeface.createFromFile(paths.first())
                        else -> buildCustomFallbackTypeface(paths)
                    }
                }.getOrNull()
                typefacesByPaths[paths] = loaded
                if (paths.isNotEmpty()) {
                    Log.i(
                        "FcitxColdStart",
                        "font key=$key files=${paths.size} loaded=${loaded != null} " +
                            "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
                    )
                }
                loaded
            }
            typefaces[key] = typeface
        }
        val sizes = snapshot.value
            .filterKeys { it.endsWith("_size") }
            .mapNotNull { (key, values) ->
                values.firstOrNull()?.trim()?.toFloatOrNull()
                    ?.coerceIn(8f, 72f)?.let { key to it }
            }
            .toMap()
        return FontCache(generation, true, typefaces, sizes)
    }

    @androidx.annotation.RequiresApi(29)
    private fun buildCustomFallbackTypeface(validPaths: List<String>): Typeface {
        val firstFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(File(validPaths[0])).build()
        ).build()
        val builder = Typeface.CustomFallbackBuilder(firstFamily)
        for (i in 1 until validPaths.size) {
            val family = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(File(validPaths[i])).build()
            ).build()
            builder.addCustomFallback(family)
        }
        return builder.build()
    }

    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            val snapshot = cache
            if (!snapshot.loaded) preloadFontsAsync()
            return snapshot.typefaces.toMutableMap()
        }

    override val fontSizeMap: MutableMap<String, Float>
        get() {
            val snapshot = cache
            if (!snapshot.loaded) preloadFontsAsync()
            return snapshot.sizes.toMutableMap()
        }

    override fun resolveTypeface(key: String, current: Typeface?): Typeface {
        val snapshot = cache
        if (!snapshot.loaded) preloadFontsAsync()
        return snapshot.typefaces[key]
            ?: snapshot.typefaces["font"]
            ?: current
            ?: Typeface.DEFAULT
    }
}
