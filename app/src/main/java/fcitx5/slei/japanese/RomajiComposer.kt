/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 slei keyboard contributors
 */
package fcitx5.slei.japanese

/**
 * Incremental romaji -> hiragana composer (pure Kotlin, no Android dependencies).
 *
 * State is a run of already converted output ([converted]; normally kana, but letters that
 * can never start a valid syllable stay there as literal romaji) followed by a short
 * [pending] romaji buffer that may still become kana.
 *
 * Rules (standard Hepburn plus common IME spellings):
 * - doubled consonant (except `n`) and `tch` produce `っ` (kka, tte, sshi, matcha)
 * - `nn` and `n'` produce `ん`; `n` before a consonant other than `y` produces `ん`;
 *   a trailing `n` becomes `ん` when the composition is finalized
 * - after `nn`, a following vowel or `y` re-uses the second `n`
 *   (konnichiha -> こんにちは, konnyaku -> こんにゃく); `nn'i` gives `んい`
 * - `x`/`l` prefixes produce small kana (xa, la, xtu, ltsu, xya, xwa ...)
 * - `-` produces the long vowel mark `ー`
 */
class RomajiComposer {

    private val converted = StringBuilder()
    private val pending = StringBuilder()

    /** True after `nn` became `ん`; a following vowel/`y` then starts a new `n` syllable. */
    private var nnAbsorbed = false

    val isEmpty: Boolean get() = converted.isEmpty() && pending.isEmpty()

    /** Romaji letters that could still become kana. */
    val pendingRomaji: String get() = pending.toString()

    /** What the preedit shows: converted kana followed by the unconverted romaji. */
    val display: String get() = converted.toString() + pending.toString()

    /** What is committed: the trailing `n` becomes `ん`, other residue stays literal. */
    val finalText: String
        get() = converted.toString() + if (pending.toString() == "n") "ん" else pending.toString()

    fun clear() {
        converted.setLength(0)
        pending.setLength(0)
        nnAbsorbed = false
    }

    /**
     * Feed one character. Letters are case-insensitive; `-` and `ー` produce `ー`,
     * `'` separates `n` from a following vowel. Returns false for characters the composer
     * does not handle (the caller should commit the composition and output them itself).
     */
    fun input(ch: Char): Boolean {
        val c = ch.lowercaseChar()
        when {
            c == '-' || c == 'ー' -> {
                flushPendingForSeparator()
                converted.append('ー')
                return true
            }
            c == '\'' -> {
                when {
                    pending.toString() == "n" -> {
                        converted.append('ん')
                        pending.setLength(0)
                    }
                    pending.isNotEmpty() -> flushPendingLiteral()
                    // "nn'" just consumes the separator: nn'i -> んい
                }
                nnAbsorbed = false
                return true
            }
            c in 'a'..'z' -> {
                if (nnAbsorbed) {
                    nnAbsorbed = false
                    if (c in VOWELS || c == 'y') pending.append('n')
                }
                pending.append(c)
                resolve()
                return true
            }
            else -> return false
        }
    }

    fun input(text: String) {
        text.forEach { input(it) }
    }

    /**
     * Delete the last unit: one pending romaji letter if any, otherwise the last converted
     * character (code point). Returns false when the composition was already empty.
     */
    fun backspace(): Boolean {
        nnAbsorbed = false
        if (pending.isNotEmpty()) {
            pending.setLength(pending.length - 1)
            return true
        }
        if (converted.isNotEmpty()) {
            val last = converted.codePointBefore(converted.length)
            converted.setLength(converted.length - Character.charCount(last))
            return true
        }
        return false
    }

    private fun flushPendingForSeparator() {
        if (pending.toString() == "n") {
            converted.append('ん')
            pending.setLength(0)
        } else if (pending.isNotEmpty()) {
            flushPendingLiteral()
        }
        nnAbsorbed = false
    }

    private fun flushPendingLiteral() {
        converted.append(pending)
        pending.setLength(0)
    }

    private fun resolve() {
        while (pending.isNotEmpty()) {
            val p = pending.toString()
            val exact = RomajiTable.MAP[p]
            val isPrefix = p in RomajiTable.PREFIXES
            if (exact != null && !isPrefix) {
                converted.append(exact)
                pending.setLength(0)
                if (p == "nn") nnAbsorbed = true
                return
            }
            if (isPrefix) return
            // p cannot grow into a table entry: apply the special rules on its head.
            val first = p[0]
            val second = p.getOrNull(1)
            when {
                second != null && first == second && first in SOKUON_CONSONANTS -> {
                    converted.append('っ')
                    pending.deleteCharAt(0)
                }
                first == 't' && second == 'c' -> {
                    converted.append('っ')
                    pending.deleteCharAt(0)
                }
                first == 'n' && second != null -> {
                    // "n" before a consonant (y/vowels/n are table prefixes, never reach here)
                    converted.append('ん')
                    pending.deleteCharAt(0)
                }
                else -> {
                    // Unconvertible letter: keep it as literal romaji.
                    converted.append(first)
                    pending.deleteCharAt(0)
                }
            }
        }
    }

    companion object {
        private const val VOWELS = "aiueo"
        private const val SOKUON_CONSONANTS = "bcdfghjkmpqrstvwyz"
    }
}

/** Hiragana <-> katakana helpers. */
object KanaConverter {
    /** Converts hiragana (ぁ..ゖ, ゝゞ) to katakana; everything else is kept. */
    fun toKatakana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    in 'ぁ'..'ゖ', 'ゝ', 'ゞ' -> ch + 0x60
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
}

/**
 * Source of conversion candidates for a hiragana reading. Kanji conversion can be added later
 * by another implementation (or by chaining providers) without changing the keyboard code.
 */
fun interface JapaneseCandidateProvider {
    fun candidates(reading: String): List<String>
}

/** ① hiragana ② katakana (deduplicated). */
object KanaCandidateProvider : JapaneseCandidateProvider {
    override fun candidates(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        return listOf(reading, KanaConverter.toKatakana(reading)).distinct()
    }
}

internal object RomajiTable {
    val MAP: Map<String, String> = buildMap {
        fun row(prefix: String, vararg kana: String) {
            "aiueo".forEachIndexed { i, v -> if (kana[i].isNotEmpty()) put(prefix + v, kana[i]) }
        }
        row("", "あ", "い", "う", "え", "お")
        row("k", "か", "き", "く", "け", "こ")
        row("s", "さ", "し", "す", "せ", "そ")
        row("t", "た", "ち", "つ", "て", "と")
        row("n", "な", "に", "ぬ", "ね", "の")
        row("h", "は", "ひ", "ふ", "へ", "ほ")
        row("m", "ま", "み", "む", "め", "も")
        row("y", "や", "い", "ゆ", "いぇ", "よ")
        row("r", "ら", "り", "る", "れ", "ろ")
        row("w", "わ", "うぃ", "う", "うぇ", "を")
        row("g", "が", "ぎ", "ぐ", "げ", "ご")
        row("z", "ざ", "じ", "ず", "ぜ", "ぞ")
        row("d", "だ", "ぢ", "づ", "で", "ど")
        row("b", "ば", "び", "ぶ", "べ", "ぼ")
        row("p", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ")
        row("f", "ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ")
        row("j", "じゃ", "じ", "じゅ", "じぇ", "じょ")
        row("v", "ゔぁ", "ゔぃ", "ゔ", "ゔぇ", "ゔぉ")
        row("c", "か", "し", "く", "せ", "こ")
        row("q", "くぁ", "くぃ", "く", "くぇ", "くぉ")
        row("ts", "つぁ", "つぃ", "つ", "つぇ", "つぉ")
        row("sh", "しゃ", "し", "しゅ", "しぇ", "しょ")
        row("ch", "ちゃ", "ち", "ちゅ", "ちぇ", "ちょ")
        row("th", "てゃ", "てぃ", "てゅ", "てぇ", "てょ")
        row("dh", "でゃ", "でぃ", "でゅ", "でぇ", "でょ")
        row("x", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ")
        row("l", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ")
        // youon
        for ((c, i) in listOf(
            "k" to "き", "s" to "し", "t" to "ち", "c" to "ち", "n" to "に", "h" to "ひ",
            "m" to "み", "r" to "り", "g" to "ぎ", "z" to "じ", "j" to "じ", "d" to "ぢ",
            "b" to "び", "p" to "ぴ", "f" to "ふ", "v" to "ゔ"
        )) {
            put("${c}ya", "${i}ゃ")
            put("${c}yi", "${i}ぃ")
            put("${c}yu", "${i}ゅ")
            put("${c}ye", "${i}ぇ")
            put("${c}yo", "${i}ょ")
        }
        put("tsu", "つ")
        put("shi", "し")
        put("chi", "ち")
        put("wyi", "ゐ")
        put("wye", "ゑ")
        put("nn", "ん")
        put("xn", "ん")
        for (p in listOf("x", "l")) {
            put("${p}ya", "ゃ")
            put("${p}yu", "ゅ")
            put("${p}yo", "ょ")
            put("${p}tu", "っ")
            put("${p}tsu", "っ")
            put("${p}wa", "ゎ")
            put("${p}ka", "ゕ")
            put("${p}ke", "ゖ")
        }
    }

    /** All proper prefixes of the table keys. */
    val PREFIXES: Set<String> = buildSet {
        for (key in MAP.keys) {
            for (len in 1 until key.length) add(key.substring(0, len))
        }
    }
}
