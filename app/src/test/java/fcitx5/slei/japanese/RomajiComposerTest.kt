/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package fcitx5.slei.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiComposerTest {

    private fun compose(romaji: String): RomajiComposer =
        RomajiComposer().apply { input(romaji) }

    private fun final(romaji: String): String = compose(romaji).finalText

    @Test
    fun basicWords() {
        assertEquals("きょうは", final("kyouha"))
        assertEquals("きって", final("kitte"))
        assertEquals("こんにちは", final("konnichiha"))
        assertEquals("しんぶん", final("shinbun"))
        assertEquals("かんい", final("kan'i"))
        assertEquals("こーひー", final("ko-hi-"))
        assertEquals("まっちゃ", final("matcha"))
        assertEquals("ざっし", final("zasshi"))
        assertEquals("こんにゃく", final("konnyaku"))
        assertEquals("みんな", final("minna"))
        assertEquals("じゃ", final("ja"))
        assertEquals("じゃ", final("jya"))
        assertEquals("ちょ", final("cho"))
        assertEquals("つ", final("tsu"))
        assertEquals("ふ", final("fu"))
        assertEquals("を", final("wo"))
        assertEquals("ぢ", final("di"))
        assertEquals("づ", final("du"))
        assertEquals("ゔ", final("vu"))
        assertEquals("てぃ", final("thi"))
    }

    @Test
    fun nRules() {
        assertEquals("ん", final("nn"))
        assertEquals("ん", compose("nn").display)
        assertEquals("かん", final("kan"))
        assertEquals("かn", compose("kan").display)
        assertEquals("んい", final("nn'i"))
        assertEquals("にゃ", final("nya"))
        assertEquals("かんじ", final("kanji"))
    }

    @Test
    fun smallKana() {
        assertEquals("っ", final("xtu"))
        assertEquals("っ", final("ltsu"))
        assertEquals("ぁ", final("la"))
        assertEquals("ぃ", final("xi"))
        assertEquals("ゃ", final("xya"))
        assertEquals("ゎ", final("xwa"))
    }

    @Test
    fun katakana() {
        assertEquals("コーヒー", KanaConverter.toKatakana(final("ko-hi-")))
        assertEquals(listOf("こーひー", "コーヒー"), KanaCandidateProvider.candidates("こーひー"))
        assertEquals(listOf("ー"), KanaCandidateProvider.candidates("ー"))
        assertEquals(emptyList<String>(), KanaCandidateProvider.candidates(""))
    }

    @Test
    fun residueStaysRomaji() {
        val c = compose("k")
        assertEquals("k", c.display)
        assertEquals("k", c.finalText)
        assertEquals("k", c.pendingRomaji)
        assertEquals("かk", compose("kak").display)
        // an unconvertible sequence keeps its letters
        assertEquals("ky", final("ky"))
    }

    @Test
    fun backspaceUnits() {
        val c = compose("kya")
        assertEquals("きゃ", c.display)
        assertTrue(c.backspace())
        assertEquals("き", c.display)
        c.input("k")
        assertEquals("きk", c.display)
        assertTrue(c.backspace())
        assertEquals("き", c.display)
        assertTrue(c.backspace())
        assertTrue(c.isEmpty)
        assertFalse(c.backspace())

        val n = compose("konn")
        assertEquals("こん", n.display)
        assertTrue(n.backspace())
        assertEquals("こ", n.display)
        n.input("i")
        assertEquals("こい", n.display)
    }

    @Test
    fun unsupportedCharactersAreRejected() {
        val c = RomajiComposer()
        assertFalse(c.input('1'))
        assertFalse(c.input('。'))
        assertTrue(c.isEmpty)
    }
}
