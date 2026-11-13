/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import java.util.concurrent.atomic.AtomicLong

interface FontProviderApi {
    fun clearCache()
    val fontTypefaceMap: MutableMap<String, Typeface?>
    val fontSizeMap: MutableMap<String, Float>

    fun resolveTypeface(key: String, current: Typeface? = null): Typeface {
        return fontTypefaceMap[key]
            ?: fontTypefaceMap["font"]
            ?: current
            ?: Typeface.DEFAULT
    }
}

object FontProviders {
    private val fontLoadMonitorLock = Any()
    private var monitoredProvider: DefaultFontProvider? = null
    private var fallbackUsedWhileLoading = false

    @Volatile
    var provider: FontProviderApi = DefaultFontProvider()
        set(value) {
            field = value
            synchronized(fontSizeResultCache) {
                fontSizeResultCache.clear()
                fontRevisionValue.incrementAndGet()
            }
            synchronized(fontLoadMonitorLock) {
                monitoredProvider = null
                fallbackUsedWhileLoading = false
            }
        }

    @Volatile
    private var needsRefresh = false
    private val fontRevisionValue = AtomicLong()
    private val fontSizeResultCache = HashMap<String, Float>()

    /** Monotonic font snapshot version for view caches that cannot share the one-shot flag. */
    val revision: Long get() = fontRevisionValue.get()

    init {
        ensureListenerRegistered()
    }

    private fun ensureListenerRegistered() {
        ConfigProviders.addFontsetListener {
            handleFontsetChanged()
        }
    }

    private fun handleFontsetChanged() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
            fontRevisionValue.incrementAndGet()
        }
        needsRefresh = true
    }

    /**
     * Mark refresh needed after saving fontset in settings.
     * Keyboard will refresh on next show via checkAndClearRefreshFlag().
     */
    fun markNeedsRefresh() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
            fontRevisionValue.incrementAndGet()
        }
        needsRefresh = true
    }

    /**
     * Check and clear refresh flag. Call when keyboard loads.
     * @return true if font changed and keyboard needs refresh
     */
    fun checkAndClearRefreshFlag(): Boolean {
        if (!needsRefresh) return false
        needsRefresh = false
        return true
    }

    fun clearCache() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
            fontRevisionValue.incrementAndGet()
        }
        needsRefresh = true
    }

    /**
     * Release reloadable font metadata under memory pressure without invalidating the
     * keyboard hierarchy. Existing TextViews retain their Typeface references, so rebuilding
     * every key when the IME window becomes hidden only creates long main-thread stalls.
     */
    fun trimMemory() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
        }
    }

    val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            requestDefaultFontLoad(recordFallback = true)
            return provider.fontTypefaceMap
        }

    val fontSizeMap: MutableMap<String, Float>
        get() {
            requestDefaultFontLoad(recordFallback = true)
            return provider.fontSizeMap
        }

    /**
     * Get font size for a specific key with fallback logic.
     * @param key The font key (e.g., "key_main_font", "cand_font")
     * @param default Default size if not configured
     * @return Font size in sp
     */
    fun getFontSize(key: String, default: Float): Float {
        requestDefaultFontLoad(recordFallback = true)
        val requestRevision = revision
        val cacheKey = "$key|$default"
        synchronized(fontSizeResultCache) {
            if (revision == requestRevision) {
                fontSizeResultCache[cacheKey]?.let { return it }
            }
        }

        val sizeMap = provider.fontSizeMap

        // First try to get specific font size for this key (e.g., "key_main_font_size"),
        // then fallback to key itself (backward compatibility), finally default.
        // Note: We intentionally do NOT fallback to "font_size" to avoid
        // overriding specific defaults (e.g., key_main_font=23sp, key_alt_font=10.67sp)
        val specificSize = sizeMap["${key}_size"]
        val size = sizeMap[key]
        val resolved = when {
            specificSize != null && specificSize in 8f..72f -> specificSize
            size != null && size in 8f..72f -> size
            else -> default
        }
        synchronized(fontSizeResultCache) {
            // A background load may replace the fallback snapshot between the read and this
            // write. Do not put the default back after the completion callback clears it.
            if (revision == requestRevision) fontSizeResultCache[cacheKey] = resolved
        }
        return resolved
    }

    /**
     * Resolve typeface with fallback chain:
     * specific key -> global "font" -> current view typeface (if provided) -> system default.
     */
    fun resolveTypeface(key: String, current: Typeface? = null): Typeface {
        requestDefaultFontLoad(recordFallback = true)
        return provider.resolveTypeface(key, current)
    }

    /** Returns true if current refresh flag is set, without consuming it. */
    fun needsRefresh(): Boolean = needsRefresh

    /** Preload fonts asynchronously. Call this before keyboard is shown. */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        val defaultProvider = provider as? DefaultFontProvider
        if (defaultProvider != null) {
            requestDefaultFontLoad(recordFallback = false)
            defaultProvider.preloadFontsAsync(onComplete)
        } else {
            onComplete?.invoke(fontTypefaceMap)
        }
    }

    private fun requestDefaultFontLoad(recordFallback: Boolean) {
        val defaultProvider = provider as? DefaultFontProvider ?: return
        val pending = !defaultProvider.isCacheLoaded()
        var attachMonitor = false
        synchronized(fontLoadMonitorLock) {
            if (pending && recordFallback) fallbackUsedWhileLoading = true
            if (pending && monitoredProvider !== defaultProvider) {
                monitoredProvider = defaultProvider
                attachMonitor = true
            }
        }
        if (!pending) return
        if (attachMonitor) {
            defaultProvider.preloadFontsAsync(
                onComplete = { onDefaultFontsLoaded(defaultProvider) },
                onFailure = { onDefaultFontLoadFailed(defaultProvider) }
            )
        } else {
            defaultProvider.preloadFontsAsync()
        }
    }

    private fun onDefaultFontsLoaded(completedProvider: DefaultFontProvider) {
        if (provider !== completedProvider) return
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
            fontRevisionValue.incrementAndGet()
        }
        val shouldRefresh = synchronized(fontLoadMonitorLock) {
            if (monitoredProvider === completedProvider) monitoredProvider = null
            val usedFallback = fallbackUsedWhileLoading
            fallbackUsedWhileLoading = false
            usedFallback
        }
        // A keyboard build may have memoized fallback sizes or typefaces while loading. The
        // existing InputView.startInput path consumes this on the next safe keyboard show.
        if (shouldRefresh) needsRefresh = true
    }

    private fun onDefaultFontLoadFailed(failedProvider: DefaultFontProvider) {
        if (provider !== failedProvider) return
        synchronized(fontLoadMonitorLock) {
            if (monitoredProvider === failedProvider) monitoredProvider = null
            // Keep fallbackUsedWhileLoading until a later successful load can refresh it.
        }
    }

    /** Check whether the current loaded font snapshot contains any configured custom face. */
    fun hasCustomFonts(): Boolean = fontTypefaceMap.values.any { it != null }

    /** Check whether the current loaded font snapshot contains configured font sizes. */
    fun hasCustomFontSizes(): Boolean = fontSizeMap.isNotEmpty()
}
