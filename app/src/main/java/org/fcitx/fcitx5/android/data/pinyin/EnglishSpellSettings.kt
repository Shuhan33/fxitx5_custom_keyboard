/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles

/**
 * Toggles pinyin "Show English Candidates" (SpellEnabled).
 */
object EnglishSpellSettings {
    fun isEnabled(): Boolean =
        AppPrefs.getInstance().keyboard.englishSpellCandidates.getValue()

    fun setEnabled(enabled: Boolean) {
        AppPrefs.getInstance().keyboard.englishSpellCandidates.setValue(enabled)
        persist(enabled)
    }

    fun toggle() {
        setEnabled(!isEnabled())
    }

    fun persist(enabled: Boolean) {
        UserConfigFiles.upsertPinyinEngineKey("SpellEnabled", if (enabled) "True" else "False")
        FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
            runCatching {
                val cfg = getAddonConfig("pinyin")
                val node = cfg.findByName("cfg") ?: cfg
                node.getOrCreate("SpellEnabled").value = if (enabled) "True" else "False"
                setAddonConfig("pinyin", cfg)
                runCatching { reloadConfig() }
            }
        }
    }
}
