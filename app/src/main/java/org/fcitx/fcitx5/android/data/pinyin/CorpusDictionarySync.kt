/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a high-frequency Chinese dictionary on first launch and imports it
 * into the pinyin engine. Failures are ignored so offline devices still work.
 */
object CorpusDictionarySync {
    private const val PREF_KEY = "corpus_dict_imported_v1"
    private const val FILE_NAME = "CustomPinyinDictionary_Fcitx.dict"
    private const val DOWNLOAD_URL =
        "https://github.com/wuhgit/CustomPinyinDictionary/releases/download/assets/CustomPinyinDictionary_Fcitx.dict"

    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences("slei_corpus", 0)
        if (prefs.getBoolean(PREF_KEY, false)) return@withContext
        val destDir = File(appContext.getExternalFilesDir(null), "data/pinyin/dictionaries")
        destDir.mkdirs()
        val dest = File(destDir, FILE_NAME)
        if (dest.exists() && dest.length() > 1_000_000L) {
            prefs.edit().putBoolean(PREF_KEY, true).apply()
            return@withContext
        }
        val tmp = File(appContext.cacheDir, "$FILE_NAME.tmp")
        runCatching {
            val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            if (tmp.length() < 1_000_000L) error("dictionary too small: ${tmp.length()}")
            val dictFile = File(appContext.cacheDir, FILE_NAME)
            if (tmp != dictFile) {
                if (dictFile.exists()) dictFile.delete()
                if (!tmp.renameTo(dictFile)) {
                    tmp.copyTo(dictFile, overwrite = true)
                    tmp.delete()
                }
            }
            PinyinDictManager.importFromFile(dictFile).getOrThrow()
            dictFile.delete()
            prefs.edit().putBoolean(PREF_KEY, true).apply()
            FcitxDaemon.getFirstConnectionOrNull()?.also {
                FcitxDaemon.restartFcitx()
            }
            Timber.i("Imported Chinese corpus dictionary")
        }.onFailure { error ->
            Timber.w(error, "Failed to import Chinese corpus dictionary")
            tmp.delete()
        }
    }
}
