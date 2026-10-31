package com.unciv.ui.components.fonts

import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly

/**
 *  Minimal Arabic text support for a framework that can only draw glyphs left-to-right, one codepoint at a time.
 *
 *  Two steps are applied to a fully translated string (see [process]):
 *  1. **Shaping**: each Arabic letter is replaced by its contextual presentation form
 *     (isolated / final / initial / medial, plus the Lam-Alef ligatures) so letters join properly.
 *     Tashkeel (short vowel marks) are dropped, since single-glyph rendering cannot position them.
 *  2. **Visual reordering**: a simplified Unicode bidi pass. The base direction is right-to-left.
 *     Arabic runs are reversed, Latin letters / digits / icons keep their internal order,
 *     the order of the runs is reversed, and brackets are mirrored.
 *
 *  This must only be applied to the *final* display text, never to text that is still going to be
 *  assembled from placeholders, which is why it is hooked into the public `String.tr` entry only.
 *
 *  Style note: the project enforces purity annotations on `tr`, so everything reachable from [process]
 *  is written with plain comparisons and loops and marked `@Readonly`.
 */
object ArabicTextProcessor {
    const val LANGUAGE = "Arabic"

    // Forms per letter: isolated, final, initial, medial (0 = form does not exist). Filled once in init.
    private val forms = HashMap<Char, IntArray>()

    private fun dual(c: Int, iso: Int) { forms[c.toChar()] = intArrayOf(iso, iso + 1, iso + 2, iso + 3) }
    private fun single(c: Int, iso: Int) { forms[c.toChar()] = intArrayOf(iso, iso + 1, 0, 0) }

    init {
        forms['\u0621'] = intArrayOf(0xFE80, 0, 0, 0)
        single(0x0622, 0xFE81); single(0x0623, 0xFE83); single(0x0624, 0xFE85); single(0x0625, 0xFE87)
        dual(0x0626, 0xFE89)
        single(0x0627, 0xFE8D)
        dual(0x0628, 0xFE8F)
        single(0x0629, 0xFE93)
        dual(0x062A, 0xFE95); dual(0x062B, 0xFE99); dual(0x062C, 0xFE9D); dual(0x062D, 0xFEA1); dual(0x062E, 0xFEA5)
        single(0x062F, 0xFEA9); single(0x0630, 0xFEAB); single(0x0631, 0xFEAD); single(0x0632, 0xFEAF)
        dual(0x0633, 0xFEB1); dual(0x0634, 0xFEB5); dual(0x0635, 0xFEB9); dual(0x0636, 0xFEBD)
        dual(0x0637, 0xFEC1); dual(0x0638, 0xFEC5); dual(0x0639, 0xFEC9); dual(0x063A, 0xFECD)
        forms['\u0640'] = intArrayOf(0x0640, 0x0640, 0x0640, 0x0640) // tatweel
        dual(0x0641, 0xFED1); dual(0x0642, 0xFED5); dual(0x0643, 0xFED9); dual(0x0644, 0xFEDD)
        dual(0x0645, 0xFEE1); dual(0x0646, 0xFEE5); dual(0x0647, 0xFEE9)
        single(0x0648, 0xFEED); single(0x0649, 0xFEEF)
        dual(0x064A, 0xFEF1)
        // Persian / Urdu extras that are commonly mixed into Arabic text
        dual(0x067E, 0xFB56); dual(0x0686, 0xFB7A); single(0x0698, 0xFB8A); dual(0x06A9, 0xFB8E)
        dual(0x06AF, 0xFB92); dual(0x06CC, 0xFBFC)
    }

    private const val DIR_R = 0
    private const val DIR_L = 1
    private const val DIR_N = 2

    /** Lam-Alef ligature: isolated form for the alef variant (final form is always +1), or 0 if [c] is no alef variant */
    @Readonly
    private fun lamAlefBase(c: Char): Int = when (c) {
        '\u0622' -> 0xFEF5
        '\u0623' -> 0xFEF7
        '\u0625' -> 0xFEF9
        '\u0627' -> 0xFEFB
        else -> 0
    }

    @Readonly
    private fun isTashkeel(c: Char) = (c >= '\u064B' && c <= '\u065F') || c == '\u0670'

    /** Arabic-Indic digits are numbers, they behave like Latin digits (left-to-right runs) */
    @Readonly
    private fun isArabicDigit(c: Char) = (c >= '\u0660' && c <= '\u0669') || (c >= '\u06F0' && c <= '\u06F9')

    @Readonly
    private fun isDigitChar(c: Char) = (c >= '0' && c <= '9') || isArabicDigit(c)

    @Readonly
    private fun isArabicLetterBlock(c: Char) =
        (c >= '\u0600' && c <= '\u06FF') || (c >= '\u0750' && c <= '\u077F') ||
        (c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF')

    @Readonly
    private fun isStrongRtl(c: Char) = isArabicLetterBlock(c) && !isArabicDigit(c)

    @Readonly
    fun containsArabic(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            if (isStrongRtl(text[i])) return true
            i++
        }
        return false
    }

    /** Entry point: shape and visually reorder [text]. Returns the input instance if it holds no Arabic. */
    @Readonly
    fun process(text: String): String {
        if (!containsArabic(text)) return text
        @LocalState val result = StringBuilder(text.length)
        var lineStart = 0
        while (true) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline
            val line = text.substring(lineStart, lineEnd)
            result.append(if (containsArabic(line)) reorder(shape(line)) else line)
            if (newline < 0) break
            result.append('\n')
            lineStart = newline + 1
        }
        return result.toString()
    }

    // ---------------------------------------------------------------- shaping

    @Readonly
    private fun canConnectToNext(c: Char): Boolean {
        val f = forms[c] ?: return false
        return f[2] != 0 // has an initial form => dual joining
    }

    @Readonly
    private fun canConnectToPrev(c: Char): Boolean {
        val f = forms[c] ?: return false
        return f[1] != 0
    }

    @Readonly
    private fun stripTashkeel(input: String): String {
        @LocalState val sb = StringBuilder(input.length)
        var k = 0
        while (k < input.length) {
            val ch = input[k]
            if (!isTashkeel(ch)) sb.append(ch)
            k++
        }
        return sb.toString()
    }

    @Readonly
    private fun shape(input: String): String {
        val s = stripTashkeel(input)
        @LocalState val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val f = forms[c]
            if (f == null) {
                sb.append(c)
                i++
                continue
            }
            val prevJoins = i > 0 && canConnectToNext(s[i - 1])
            // Lam-Alef ligature
            if (c == '\u0644' && i + 1 < s.length) {
                val base = lamAlefBase(s[i + 1])
                if (base != 0) {
                    sb.append((if (prevJoins) base + 1 else base).toChar())
                    i += 2
                    continue
                }
            }
            val nextJoins = f[2] != 0 && i + 1 < s.length && canConnectToPrev(s[i + 1])
            val code = if (prevJoins && nextJoins) f[3] else if (prevJoins) f[1] else if (nextJoins) f[2] else f[0]
            sb.append((if (code != 0) code else f[0]).toChar())
            i++
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- bidi

    /** Whitespace and punctuation take the direction of their surroundings. Everything else that isn't Arabic (letters, digits, icons) is left-to-right. */
    @Readonly
    private fun isNeutral(c: Char): Boolean {
        if (c == ' ' || c == '\t' || c == '\u00A0') return true
        if (c >= '\u2000' && c <= '\u206F') return true // general punctuation: dashes, quotes, ellipsis, thin spaces
        return when (c) {
            '.', ',', ':', ';', '!', '?', '(', ')', '[', ']', '{', '}', '<', '>', '/', '\\', '-', '+', '%',
            '\'', '"', '\u00AB', '\u00BB', '*', '=', '&', '#', '@', '~', '|', '_' -> true
            else -> false
        }
    }

    @Readonly
    private fun mirror(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '<' -> '>'
        '>' -> '<'
        '\u00AB' -> '\u00BB'
        '\u00BB' -> '\u00AB'
        else -> c
    }

    @Readonly
    private fun reorder(s: String): String {
        val n = s.length
        @LocalState val type = IntArray(n)
        var i = 0
        while (i < n) {
            val c = s[i]
            type[i] = if (isStrongRtl(c)) DIR_R else if (isNeutral(c)) DIR_N else DIR_L
            i++
        }
        // Signs and separators that belong to a number stay with it
        i = 0
        while (i < n) {
            if (type[i] == DIR_N) {
                val c = s[i]
                val prevDigit = i > 0 && isDigitChar(s[i - 1])
                val nextDigit = i + 1 < n && isDigitChar(s[i + 1])
                if ((c == '+' || c == '-') && nextDigit) type[i] = DIR_L
                else if ((c == '.' || c == ',' || c == ':') && prevDigit && nextDigit) type[i] = DIR_L
                else if (c == '%' && prevDigit) type[i] = DIR_L
                else if (c == '/' && prevDigit && nextDigit) type[i] = DIR_L
            }
            i++
        }
        // Resolve neutrals: between equal directions they take that direction, otherwise the base direction (R)
        i = 0
        while (i < n) {
            if (type[i] != DIR_N) {
                i++
                continue
            }
            var j = i
            while (j < n && type[j] == DIR_N) j++
            val leftType = if (i > 0) type[i - 1] else DIR_R
            val rightType = if (j < n) type[j] else DIR_R
            val resolved = if (leftType == rightType) leftType else DIR_R
            var k = i
            while (k < j) {
                type[k] = resolved
                k++
            }
            i = j
        }
        // Split into runs of equal direction
        @LocalState val runStart = IntArray(n)
        @LocalState val runEnd = IntArray(n)
        var runCount = 0
        i = 0
        while (i < n) {
            var j = i
            while (j < n && type[j] == type[i]) j++
            runStart[runCount] = i
            runEnd[runCount] = j
            runCount++
            i = j
        }
        // Emit runs in reverse order; Arabic runs reversed internally, left-to-right runs kept
        @LocalState val sb = StringBuilder(n)
        var r = runCount - 1
        while (r >= 0) {
            val a = runStart[r]
            val b = runEnd[r]
            if (type[a] == DIR_R) {
                var p = b - 1
                while (p >= a) {
                    sb.append(mirror(s[p]))
                    p--
                }
            } else {
                var p = a
                while (p < b) {
                    sb.append(s[p])
                    p++
                }
            }
            r--
        }
        return sb.toString()
    }
}
