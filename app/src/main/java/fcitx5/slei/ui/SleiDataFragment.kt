/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Shuhan Lei
 */
package fcitx5.slei.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import fcitx5.slei.performance.SleiPerformanceMetrics
import fcitx5.slei.stats.InputStatsManager
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SleiDataFragment : PaddingPreferenceFragment() {
    private var overviewPreference: Preference? = null
    private var topPhrasesPreference: Preference? = null
    private var directoryPreference: Preference? = null

    private val prefs by lazy {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(::exportTo) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.slei_stats_import)
            .setItems(arrayOf(getString(R.string.slei_stats_merge), getString(R.string.slei_stats_replace))) { _, which ->
                importFrom(uri, merge = which == 0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val directoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(BACKUP_TREE_KEY, uri.toString()).apply()
        }.onFailure(::showError)
        refreshDirectorySummary()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(R.string.slei_stats_category) {
                overviewPreference = Preference(context).apply {
                    title = getString(R.string.slei_stats_overview)
                    isSelectable = false
                }.also { addPreference(it) }
                topPhrasesPreference = Preference(context).apply {
                    title = getString(R.string.slei_stats_top_phrases)
                    isSelectable = false
                }.also { addPreference(it) }
                addPreference(R.string.slei_stats_export) { exportStats() }
                addPreference(R.string.slei_stats_import) {
                    importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                }
                addPreference(R.string.slei_stats_clear) { showClearDialog() }
            }
            addCategory(R.string.slei_personalization_category) {
                addPreference(R.string.slei_personal_phrases, R.string.slei_personal_phrases_summary) {
                    navigateWithAnim(SettingsRoute.PinyinCustomPhrase)
                }
                addPreference(R.string.slei_dictionary_manager, R.string.slei_dictionary_manager_summary) {
                    navigateWithAnim(SettingsRoute.PinyinDict(""))
                }
                directoryPreference = Preference(context).apply {
                    title = getString(R.string.slei_backup_directory)
                    setOnPreferenceClickListener {
                        directoryLauncher.launch(selectedTreeUri())
                        true
                    }
                }.also { addPreference(it) }
            }
            addCategory(R.string.slei_performance_category) {
                addPreference(
                    getString(R.string.slei_performance_diagnostics),
                    SleiPerformanceMetrics.summary()
                )
            }
        }
        refreshDirectorySummary()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        if (overviewPreference == null) return
        lifecycleScope.launch {
            val data = InputStatsManager.snapshot()
            overviewPreference?.summary = getString(
                R.string.slei_stats_overview_summary,
                data.totalCharacters,
                data.totalCommits,
                data.dailyCharacters[today()] ?: 0L
            )
            topPhrasesPreference?.summary = data.phraseFrequency.entries
                .sortedByDescending { it.value }
                .take(10)
                .joinToString("\n") { "${it.key}  ·  ${it.value}" }
                .ifBlank { getString(R.string.slei_stats_empty) }
        }
    }

    private fun exportStats() {
        val tree = selectedTreeUri()?.let { DocumentFile.fromTreeUri(requireContext(), it) }
        if (tree?.canWrite() == true) {
            val name = exportFileName()
            val file = tree.createFile("application/json", name)
            if (file != null) exportTo(file.uri) else showError(IllegalStateException(getString(R.string.slei_export_failed)))
        } else {
            exportLauncher.launch(exportFileName())
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                requireContext().contentResolver.openOutputStream(uri, "wt")!!.use {
                    InputStatsManager.exportTo(it)
                }
            }.onSuccess {
                Toast.makeText(requireContext(), R.string.slei_export_done, Toast.LENGTH_SHORT).show()
            }.onFailure(::showError)
        }
    }

    private fun importFrom(uri: Uri, merge: Boolean) {
        lifecycleScope.launch {
            runCatching {
                requireContext().contentResolver.openInputStream(uri)!!.use {
                    InputStatsManager.importFrom(it, merge)
                }
            }.onSuccess {
                Toast.makeText(requireContext(), R.string.slei_import_done, Toast.LENGTH_SHORT).show()
                refreshStats()
            }.onFailure(::showError)
        }
    }

    private fun showClearDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.slei_stats_clear)
            .setItems(
                arrayOf(
                    getString(R.string.slei_clear_counters),
                    getString(R.string.slei_clear_phrases),
                    getString(R.string.slei_clear_all)
                )
            ) { _, which ->
                val mode = when (which) {
                    0 -> InputStatsManager.ClearMode.Counters
                    1 -> InputStatsManager.ClearMode.Phrases
                    else -> InputStatsManager.ClearMode.All
                }
                lifecycleScope.launch {
                    InputStatsManager.clear(mode)
                    refreshStats()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectedTreeUri(): Uri? = prefs.getString(BACKUP_TREE_KEY, null)?.let(Uri::parse)

    private fun refreshDirectorySummary() {
        directoryPreference?.summary = selectedTreeUri()?.toString()
            ?: getString(R.string.slei_backup_directory_summary)
    }

    private fun showError(error: Throwable) {
        Toast.makeText(requireContext(), error.message ?: getString(R.string.unknown_error), Toast.LENGTH_LONG).show()
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    private fun exportFileName() = "slei-input-stats-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.json"

    companion object {
        private const val BACKUP_TREE_KEY = "slei_backup_tree_uri"
    }
}
