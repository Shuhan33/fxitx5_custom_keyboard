/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException

object UserConfigFiles {
    const val DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE = "default"
    private const val SLEI_CUSTOM_REV = 9
    private const val TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME = "TextKeyboardLayout.json"
    private const val TEXT_KEYBOARD_LAYOUT_PREFIX = "TextKeyboardLayout."
    private const val JSON_SUFFIX = ".json"
    private val TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME = Regex(
        "^TextKeyboardLayout(?:\\..+)?_backup_\\d{8}_\\d{6}(?:_.*)?\\.json$"
    )

    private fun externalFilesRoot(): File? = appContext.getExternalFilesDir(null)

    fun configDir(): File? = externalFilesRoot()?.let { File(it, "config") }

    fun fontsDir(): File? = externalFilesRoot()?.let { File(it, "fonts") }

    fun textKeyboardLayoutJson(): File? = textKeyboardLayoutJson(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)

    fun textKeyboardLayoutJson(profile: String): File? {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: return null
        val fileName = if (normalized == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$normalized$JSON_SUFFIX"
        }
        return configDir()?.let { File(it, fileName) }
    }

    fun normalizeTextKeyboardLayoutProfile(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val sanitized = trimmed
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "")
            .trim()
            .trim('.')
        if (sanitized.isEmpty()) return null
        return if (sanitized.equals(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, ignoreCase = true)) {
            DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        } else {
            sanitized
        }
    }

    fun listTextKeyboardLayoutProfiles(): List<String> {
        val dir = configDir() ?: return listOf(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        val fileNames = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toList()
            .orEmpty()

        val profiles = mutableSetOf<String>()
        if (fileNames.any { it == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME }) {
            profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        }
        fileNames.forEach { name ->
            if (TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME.matches(name)) return@forEach
            if (name == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME) return@forEach
            if (name.startsWith(TEXT_KEYBOARD_LAYOUT_PREFIX) && name.endsWith(JSON_SUFFIX)) {
                val rawProfile = name.removePrefix(TEXT_KEYBOARD_LAYOUT_PREFIX).removeSuffix(JSON_SUFFIX)
                val profile = normalizeTextKeyboardLayoutProfile(rawProfile)
                if (profile != null && profile != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                    profiles += profile
                }
            }
        }

        profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return profiles.toList().sortedWith(compareBy({ it != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
    }

    fun textKeyboardLayoutFileName(profile: String): String {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return if (normalized == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$normalized$JSON_SUFFIX"
        }
    }

    fun popupPresetJson(): File? = configDir()?.let { File(it, "PopupPreset.json") }

    fun fontsetJson(): File? = fontsDir()?.let { File(it, "fontset.json") }
    
    fun kawaiiBarButtonsConfig(): File? = configDir()?.let { File(it, "KawaiiBarButtonsLayout.json") }

    fun statusAreaButtonsConfig(): File? = configDir()?.let { File(it, "StatusAreaButtonsLayout.json") }

    /**
     * Unified buttons layout configuration file.
     * Replaces separate KawaiiBarButtonsLayout.json and StatusAreaButtonsLayout.json files.
     */
    fun buttonsLayoutConfig(): File? = configDir()?.let { File(it, "ButtonsLayout.json") }

    /**
     * Copy bundled keyboard layout and popup presets into the user config dir
     * when those files do not exist yet, so first launch matches the Apple layout.
     * Also applies one-time slei customizations (emoji key, Xiaohe shuangpin).
     */
    fun seedBundledDefaultsIfMissing() {
        copyRawIfMissing(R.raw.text_keyboard_layout, textKeyboardLayoutJson())
        copyRawIfMissing(R.raw.popup_preset, popupPresetJson())
        applySleiCustomizationsIfNeeded()
    }

    private fun sleiRevFile(): File? = configDir()?.let { File(it, ".slei_custom_rev") }

    private fun applySleiCustomizationsIfNeeded() {
        val revFile = sleiRevFile() ?: return
        val current = revFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (current >= SLEI_CUSTOM_REV) return
        runCatching {
            revFile.parentFile?.mkdirs()
            migrateBottomRowKeys()
            migrateVSwipeHint()
            injectEmojiToolbarButtonIfMissing()
            patchPinyinEngineDefaults()
            mergeCommonPronunciationAliases()
            mergeKanaPopupPreset()
            seedDefaultImProfile()
            revFile.writeText(SLEI_CUSTOM_REV.toString())
        }
    }

    private fun migrateBottomRowKeys() {
        val file = textKeyboardLayoutJson() ?: return
        if (!file.exists()) return
        val original = file.readText()
        var updated = original
            .replace(Regex("""\s*,\s*\{"type":\s*"LayoutSwitchKey",\s*"label":\s*"あ"[^}]*\}"""), "")
            .replace(Regex("""\{"type":\s*"LayoutSwitchKey",\s*"label":\s*"あ"[^}]*\}\s*,\s*"""), "")
            .replace(Regex("""\s*,\s*\{"type":\s*"EnglishSpellToggleKey"[^}]*\}"""), "")
            .replace(Regex("""\{"type":\s*"EnglishSpellToggleKey"[^}]*\}\s*,\s*"""), "")
        if (updated != original) file.writeText(updated)
    }

    /**
     * Old installations keep their seeded layout forever. Revision 9 repairs the
     * visible V swipe hint without replacing any other user layout customization.
     */
    private fun migrateVSwipeHint() {
        val file = textKeyboardLayoutJson() ?: return
        if (!file.exists()) return
        val original = file.readText()
        val vKey = Regex(
            """(\{[^{}]*\"type\"\s*:\s*\"AlphabetKey\"[^{}]*\"main\"\s*:\s*\"[Vv]\"[^{}]*\"alt\"\s*:\s*\")[\-–—−](\"[^{}]*\})"""
        )
        val updated = original.replace(vKey, "$1_$2")
        if (updated != original) file.writeText(updated)
    }

    /**
     * Small, context-specific aliases for common pronunciation mistakes. They are
     * deliberately exact phrase mappings instead of a global mo/mu fuzzy rule,
     * which would add noisy candidates to unrelated syllables. Existing user
     * custom phrases always win and are never overwritten.
     */
    private fun mergeCommonPronunciationAliases() {
        val file = externalFilesRoot()?.let { File(it, "data/pinyin/customphrase") } ?: return
        file.parentFile?.mkdirs()
        val original = if (file.exists()) file.readText() else ""
        val existingKeys = original.lineSequence()
            .mapNotNull { line -> line.substringBefore(',').trim().takeIf { it.matches(Regex("[A-Za-z]+")) } }
            .map(String::lowercase)
            .toHashSet()
        val aliases = linkedMapOf(
            // Full pinyin and Xiaohe Shuangpin spellings for “mo ban”.
            "moban" to "模板",
            "mobj" to "模板"
        )
        val additions = aliases.filterKeys { it !in existingKeys }
        if (additions.isEmpty()) return
        file.appendText(buildString {
            if (original.isNotEmpty() && !original.endsWith('\n')) append('\n')
            append("; slei 1.0.1 common pronunciation aliases\n")
            additions.forEach { (key, phrase) -> append(key).append(",3=").append(phrase).append('\n') }
        })
    }

    fun syncHistoryWeightPercent(percent: Int) {
        upsertPinyinEngineKey("HistoryWeightPercent", percent.coerceIn(10, 90).toString())
    }

    fun upsertPinyinEngineKey(key: String, value: String) {
        val conf = configDir()?.let { File(it, "conf/pinyin.conf") } ?: return
        conf.parentFile?.mkdirs()
        val original = if (conf.exists()) conf.readText() else ""
        val updated = upsertRootIniKey(original, key, value)
        if (updated != original) conf.writeText(updated)
    }

    private fun injectEmojiToolbarButtonIfMissing() {
        val file = buttonsLayoutConfig() ?: return
        if (!file.exists()) return
        val text = file.readText()
        if (text.contains("\"id\": \"emoji\"") || text.contains("\"id\":\"emoji\"")) return
        val clipboard = """{"id": "clipboard"}"""
        val compactClipboard = """{"id":"clipboard"}"""
        when {
            text.contains(clipboard) ->
                file.writeText(text.replaceFirst(clipboard, """{"id": "clipboard"},{"id": "emoji"}"""))
            text.contains(compactClipboard) ->
                file.writeText(text.replaceFirst(compactClipboard, """{"id":"clipboard"},{"id":"emoji"}"""))
        }
    }

    private fun patchPinyinEngineDefaults() {
        val conf = configDir()?.let { File(it, "conf/pinyin.conf") } ?: return
        conf.parentFile?.mkdirs()
        val original = if (conf.exists()) conf.readText() else ""
        var updated = original
        updated = upsertRootIniKey(updated, "ShuangpinProfile", "Xiaohe")
        updated = upsertRootIniKey(updated, "ChaiziEnabled", "False")
        updated = upsertRootIniKey(updated, "StrokeCandidateEnabled", "False")
        updated = upsertRootIniKey(updated, "ExtBEnabled", "False")
        updated = upsertRootIniKey(updated, "Prediction", "True")
        val spellEnabled = runCatching {
            org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
                .keyboard.englishSpellCandidates.getValue()
        }.getOrDefault(true)
        updated = upsertRootIniKey(updated, "SpellEnabled", if (spellEnabled) "True" else "False")
        val weightPercent = runCatching {
            org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
                .advanced.userHistoryWeightPercent.getValue()
        }.getOrDefault(45)
        updated = upsertRootIniKey(
            updated,
            "HistoryWeightPercent",
            weightPercent.coerceIn(10, 90).toString()
        )
        if (updated != original) {
            conf.writeText(updated)
        }
    }

    private fun upsertRootIniKey(text: String, key: String, value: String): String {
        // fcitx's pinyin.conf options live at the root. Remove old copies even
        // when an earlier custom build accidentally wrote them below a section,
        // then prepend the canonical root value before every section header.
        val keyLine = Regex(
            """^\s*${Regex.escape(key)}\s*=.*(?:\r?\n|$)""",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        val withoutOldValue = text.replace(keyLine, "").trimStart('\r', '\n')
        return buildString {
            append(key).append('=').append(value).append('\n')
            append(withoutOldValue)
            if (withoutOldValue.isNotEmpty() && !withoutOldValue.endsWith('\n')) append('\n')
        }
    }

    private fun mergeKanaPopupPreset() {
        val file = popupPresetJson() ?: return
        if (!file.exists()) return
        val original = file.readText()
        if (original.contains("\"あ\"")) return
        val kana = """
  ,"あ": ["い", "う", "え", "お", "ぁ"],
  "か": ["き", "く", "け", "こ", "が"],
  "さ": ["し", "す", "せ", "そ", "ざ"],
  "た": ["ち", "つ", "て", "と", "だ"],
  "な": ["に", "ぬ", "ね", "の"],
  "は": ["ひ", "ふ", "へ", "ほ", "ば", "ぱ"],
  "ま": ["み", "む", "め", "も"],
  "や": ["ゆ", "よ", "ゃ", "ゅ", "ょ"],
  "ら": ["り", "る", "れ", "ろ"],
  "わ": ["を", "ん", "ー", "っ", "ゎ"]
""".trimIndent()
        val updated = original.replace(Regex("""\}\s*$"""), "$kana\n}")
        if (updated != original) file.writeText(updated)
    }

    private fun seedDefaultImProfile() {
        val file = configDir()?.let { File(it, "profile") } ?: return
        if (file.exists()) return
        file.writeText(
            """
            [Groups/0]
            Name=Default
            Default Layout=us
            DefaultIM=pinyin

            [Groups/0/Items/0]
            Name=pinyin
            Layout=us

            [Groups/0/Items/1]
            Name=shuangpin
            Layout=us

            [GroupOrder]
            0=Default
            """.trimIndent() + "\n"
        )
    }

    private fun copyRawIfMissing(rawRes: Int, target: File?) {
        if (target == null || target.exists()) return
        runCatching {
            target.parentFile?.mkdirs()
            appContext.resources.openRawResource(rawRes).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            if (error !is IOException) throw error
        }
    }
}
