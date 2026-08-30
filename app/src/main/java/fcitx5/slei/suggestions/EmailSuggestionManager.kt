/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.suggestions

import android.content.Context
import androidx.preference.PreferenceManager

data class EmailSuggestion(val label: String, val commitSuffix: String)

object EmailSuggestionManager {
    const val PREF_DOMAINS = "slei_email_suggestion_domains"
    const val DEFAULT_DOMAINS = "hotmail.com, qq.com, 163.com, gmail.com, outlook.com"

    private val completeEmail = Regex(
        "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}"
    )
    private val emailPrefix = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]*$")

    fun suggestions(context: Context, textBeforeCursor: String): List<EmailSuggestion> {
        rememberCompletedEmails(context, textBeforeCursor)
        val token = emailPrefix.find(textBeforeCursor.takeLast(160))?.value ?: return emptyList()
        val at = token.lastIndexOf('@')
        if (at <= 0) return emptyList()
        val typedDomain = token.substring(at + 1)
        val learned = learnedEmails(context)
            .asSequence()
            .filter { it.startsWith(token, ignoreCase = true) && it.length > token.length }
            .map { EmailSuggestion(it, it.substring(token.length)) }
        val domains = configuredDomains(context)
            .asSequence()
            .filter { it.startsWith(typedDomain, ignoreCase = true) && it.length > typedDomain.length }
            .map { EmailSuggestion(it, it.substring(typedDomain.length)) }
        return (learned + domains)
            .distinctBy { it.label.lowercase() }
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    fun hasActivePrefix(textBeforeCursor: String): Boolean =
        emailPrefix.containsMatchIn(textBeforeCursor.takeLast(160))

    fun configuredDomains(context: Context): List<String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_DOMAINS, DEFAULT_DOMAINS)
            .orEmpty()
        return raw.split(',', '，', '\n', ';', '；')
            .map { it.trim().removePrefix("@") }
            .filter { DOMAIN.matches(it) }
            .distinctBy { it.lowercase() }
            .take(MAX_DOMAINS)
    }

    private fun rememberCompletedEmails(context: Context, text: String) {
        val found = completeEmail.findAll(text.takeLast(320)).map { it.value }.toList()
        if (found.isEmpty()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val existing = prefs.getStringSet(PREF_LEARNED_EMAILS, emptySet()).orEmpty()
        if (found.all(existing::contains)) return
        val merged = (existing + found)
            .toList()
            .takeLast(MAX_LEARNED_EMAILS)
            .toSet()
        prefs.edit().putStringSet(PREF_LEARNED_EMAILS, merged).apply()
    }

    private fun learnedEmails(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_LEARNED_EMAILS, emptySet())
            .orEmpty()

    private const val PREF_LEARNED_EMAILS = "slei_learned_email_addresses"
    private const val MAX_DOMAINS = 30
    private const val MAX_LEARNED_EMAILS = 50
    private const val MAX_SUGGESTIONS = 8
    private val DOMAIN = Regex("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")
}
