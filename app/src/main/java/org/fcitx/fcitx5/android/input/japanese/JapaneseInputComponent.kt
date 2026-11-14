/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package org.fcitx.fcitx5.android.input.japanese

import fcitx5.slei.japanese.JapaneseCandidateProvider
import fcitx5.slei.japanese.KanaCandidateProvider
import fcitx5.slei.japanese.RomajiComposer
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.JapaneseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

/**
 * Japanese romaji input implemented on the Kotlin side.
 *
 * Japanese is entered from the space-key swipe and temporarily overlays the currently active
 * fcitx input method. While Japanese is active fcitx stays on that exact input method; no key
 * of the Japanese keyboard is sent to fcitx, and leaving Japanese returns to the same anchor.
 *
 * Composition: [RomajiComposer] produces the reading; the composing text is shown inline via
 * [FcitxInputMethodService.updateLocalComposingText] and in the preedit bar, and the
 * candidate strip shows the [candidateProvider] results (hiragana, katakana).
 */
class JapaneseInputComponent :
    UniqueComponent<JapaneseInputComponent>(), Dependent, InputBroadcastReceiver,
    FcitxInputMethodService.LocalComposer, ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val preeditEmptyState: PreeditEmptyStateComponent by manager.must()
    private val broadcaster: InputBroadcaster by manager.must()
    private val preedit: PreeditComponent by manager.must()

    private val composer = RomajiComposer()

    /** Replaceable so a kanji converter can be plugged in later. */
    var candidateProvider: JapaneseCandidateProvider = KanaCandidateProvider

    private var candidates: List<String> = emptyList()

    private val modeState = JapaneseInputModeState()

    override val isActive: Boolean
        get() = modeState.isActive

    /** fcitx input method that stays active underneath the Japanese keyboard. */
    private val anchorIme: String?
        get() = modeState.anchorIme

    private val keyboardWindow: KeyboardWindow?
        get() = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow

    // ---- language switching ---------------------------------------------------------------

    /** Enter Japanese mode on top of [anchor] (the currently active fcitx input method). */
    fun enter(anchor: String) {
        if (!modeState.enter(anchor)) return
        composer.clear()
        candidates = emptyList()
        // Keep whatever the engine underneath was composing (e.g. an English word with spell
        // hints) in the editor as-is, like other non-Chinese commits; otherwise the first
        // refreshUi() below would replace it with the empty Japanese composition.
        service.finishComposing()
        service.localComposer = this
        preedit.setUndoIndicatorEnabled(false)
        // Drop anything the engine underneath still shows (e.g. prediction); its events are
        // ignored from now on, so clear the UI explicitly as well.
        service.postFcitxJob { reset() }
        horizontalCandidate.clearLocalCandidates()
        refreshUi()
        keyboardWindow?.switchLayout(
            JapaneseKeyboard.Name,
            remember = false,
            inheritTextHeight = false
        )
    }

    /** Space swipe or the ordinary language key returns to the exact underlying input method. */
    fun leaveToAnchorInputMethod() {
        if (!isActive) return
        val previousIme = anchorIme
        commitComposition()
        deactivate()
        // fcitx stayed on [previousIme] while the local Japanese composer was active. Do not
        // enumerate or reactivate here: an external IME selection must remain authoritative.
        if (previousIme != null) switchToTextKeyboard()
    }

    /** Toggle Japanese from the space-key swipe while preserving this Android editor session. */
    fun toggleFromSpaceSwipe() {
        if (isActive) {
            leaveToAnchorInputMethod()
            return
        }
        val session = service.inputSessionGenerationSnapshot
        val anchor = runCatching {
            fcitx.runImmediately { inputMethodEntryCached.uniqueName }
        }.getOrNull() ?: return
        if (session != service.inputSessionGenerationSnapshot) return
        enter(anchor)
    }

    /** Session end (keyboard hidden / editor finished): commit and leave fcitx on its anchor. */
    override fun finishSession() {
        if (!isActive) return
        val previousIme = anchorIme
        commitComposition()
        deactivate()
        if (previousIme != null) switchToTextKeyboard()
    }

    /**
     * Called before the keyboard is (re)started for an editor. Normally [finishSession] already
     * left Japanese mode; this is a safety net that never writes into the new editor (the
     * service already reset its composing state in onStartInput).
     */
    fun onNewInputSession() {
        if (!isActive) return
        val previousIme = anchorIme
        // The service has already reset the old editor's composing state. Drop local state
        // without committing that reading into the newly focused editor or changing its IME.
        deactivate()
        if (previousIme != null) switchToTextKeyboard()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        // Another path (input method picker, space long-press, toolbar...) changed the fcitx
        // input method: Japanese mode cannot stay on top of a different engine.
        if (isActive && ime.uniqueName != anchorIme) {
            commitComposition()
            deactivate()
            switchToTextKeyboard()
        }
    }

    private fun deactivate() {
        modeState.deactivate()
        composer.clear()
        candidates = emptyList()
        if (service.localComposer === this) service.localComposer = null
        preedit.setUndoIndicatorEnabled(true)
        horizontalCandidate.clearLocalCandidates()
        // Re-sync the bar/preedit with what fcitx currently holds (normally empty).
        val panel = fcitx.runImmediately { inputPanelCached }
        preeditEmptyState.updatePreeditEmptyState()
        broadcaster.onInputPanelUpdate(panel)
    }

    private fun switchToTextKeyboard() {
        keyboardWindow?.switchLayout(
            TextKeyboard.Name,
            remember = false,
            inheritTextHeight = false
        )
    }

    // ---- key handling --------------------------------------------------------------------

    /**
     * Handle an action from the Japanese keyboard. Returns true when consumed; otherwise the
     * caller continues with the normal handling (after any composition was committed here).
     */
    fun onKeyAction(action: KeyAction): Boolean {
        if (!isActive) return false
        when (action) {
            is KeyAction.FcitxKeyAction -> {
                if (action.up) return true
                val text = action.act
                if (text.length == 1 && isComposerChar(text[0])) {
                    composer.input(text[0])
                    refreshUi()
                } else {
                    commitThenText(text)
                }
                return true
            }
            is KeyAction.ComposeLiteralAction -> {
                commitThenText(action.text)
                return true
            }
            is KeyAction.CommitAction -> {
                commitThenText(action.text)
                return true
            }
            is KeyAction.DirectCommitAction -> {
                commitThenText(action.text)
                return true
            }
            is KeyAction.SymAction -> return onSym(action)
            is KeyAction.BackspaceHoldStartAction -> {
                if (!composer.isEmpty) {
                    clearComposition()
                    return true
                }
                // empty composition: CommonKeyActionListener repeats direct editor deletion
                return false
            }
            is KeyAction.BackspaceHoldEndAction,
            // This is a release-only cleanup emitted on every space-key up/cancel. Let the
            // common listener stop voice hold without committing Japanese text; the following
            // space tap must still be able to select the current candidate.
            is KeyAction.VoiceInputHoldEnd,
            is KeyAction.MoveSelectionAction -> return false
            is KeyAction.DeleteSelectionAction -> {
                // backspace swipe-left over a composition discards it (CommonKeyActionListener
                // only resets the fcitx engine underneath, which holds nothing)
                if (!composer.isEmpty && action.totalCnt < 0) clearComposition()
                return false
            }
            is KeyAction.ClearPreeditAction -> {
                clearComposition()
                return true
            }
            // No Shift on the Japanese keyboard; macro Caps actions are ignored here.
            is KeyAction.CapsAction -> return true
            else -> {
                // Layout/picker/language switches, quick phrase, voice ...: the composition must
                // not stay in the editor where another keyboard's commit would replace it.
                commitComposition()
                return false
            }
        }
    }

    private fun onSym(action: KeyAction.SymAction): Boolean {
        when (action.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace -> {
                if (composer.isEmpty) {
                    service.handleBackspaceDirectly()
                } else {
                    composer.backspace()
                    refreshUi()
                }
                return true
            }
            FcitxKeyMapping.FcitxKey_space -> {
                if (composer.isEmpty) {
                    service.commitText(" ")
                } else {
                    selectCandidate(0)
                }
                return true
            }
            FcitxKeyMapping.FcitxKey_Return, FcitxKeyMapping.FcitxKey_KP_Enter -> {
                if (composer.isEmpty) return false // normal Enter via the engine underneath
                // Enter confirms the composition as shown (hiragana) without a newline.
                commitComposition()
                return true
            }
            else -> {
                commitComposition()
                return false
            }
        }
    }

    private fun isComposerChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '-' || c == 'ー' || (c == '\'' && !composer.isEmpty)

    // ---- composition ---------------------------------------------------------------------

    private fun selectCandidate(index: Int) {
        if (!isActive) return
        val text = candidates.getOrNull(index) ?: return
        composer.clear()
        service.commitText(text)
        refreshUi()
    }

    /** Commit the composition as hiragana (trailing n -> ん). Safe to call when empty. */
    fun commitComposition() {
        if (!isActive || composer.isEmpty) return
        val text = composer.finalText
        composer.clear()
        service.commitText(text)
        refreshUi()
    }

    private fun commitThenText(text: String) {
        commitComposition()
        if (text.isNotEmpty()) service.commitText(text)
    }

    private fun clearComposition() {
        if (composer.isEmpty) return
        composer.clear()
        refreshUi()
    }

    override fun onComposingFinishedExternally() {
        // The editor already holds the shown text; just forget the local state.
        composer.clear()
        refreshUi()
    }

    private fun refreshUi() {
        if (!isActive) return
        val display = composer.display
        val reading = composer.finalText
        candidates = if (reading.isEmpty()) emptyList() else candidateProvider.candidates(reading)
        service.updateLocalComposingText(display)
        val preeditText = if (display.isEmpty()) {
            FormattedText.Empty
        } else {
            FormattedText(arrayOf(display), intArrayOf(0), display.length)
        }
        preeditEmptyState.updatePreeditEmptyState(clientPreedit = preeditText, preedit = preeditText)
        broadcaster.onInputPanelUpdate(
            FcitxEvent.InputPanelEvent.Data(
                preeditText,
                FormattedText.Empty,
                FormattedText.Empty,
                emptyArray()
            )
        )
        if (candidates.isEmpty()) {
            horizontalCandidate.clearLocalCandidates()
        } else {
            horizontalCandidate.showLocalCandidates(candidates) { selectCandidate(it) }
        }
    }

}
