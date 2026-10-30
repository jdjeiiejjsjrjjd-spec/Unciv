package com.unciv.ui.components.fonts

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
 */
object ArabicTextProcessor {
    const val LANGUAGE = "Arabic"

    // Forms: isolated, final, initial, medial (0 = form does not exist)
    private val forms = HashMap<Char, IntArray>()

    private fun dual(c: Int, iso: Int) { forms[c.toChar()] = intArrayOf(iso, iso + 1, iso + 2, iso + 3) }
    private fun right(c: Int, iso: Int) { forms[c.toChar()] = intArrayOf(iso, iso + 1, 0, 0) }

    init {
        forms['\u0621'] = intArrayOf(0xFE80, 0, 0, 0)
        right(0x0622, 0xFE81); right(0x0623, 0xFE83); right(0x0624, 0xFE85); right(0x0625, 0xFE87)
        dual(0x0626, 0xFE89)
        right(0x0627, 0xFE8D)
        dual(0x0628, 0xFE8F)
        right(0x0629, 0xFE93)
        dual(0x062A, 0xFE95); dual(0x062B, 0xFE99); dual(0x062C, 0xFE9D); dual(0x062D, 0xFEA1); dual(0x062E, 0xFEA5)
        right(0x062F, 0xFEA9); right(0x0630, 0xFEAB); right(0x0631, 0xFEAD); right(0x0632, 0xFEAF)
        dual(0x0633, 0xFEB1); dual(0x0634, 0xFEB5); dual(0x0635, 0xFEB9); dual(0x0636, 0xFEBD)
        dual(0x0637, 0xFEC1); dual(0x0638, 0xFEC5); dual(0x0639, 0xFEC9); dual(0x063A, 0xFECD)
        forms['\u0640'] = intArrayOf(0x0640, 0x0640, 0x0640, 0x0640) // tatweel
        dual(0x0641, 0xFED1); dual(0x0642, 0xFED5); dual(0x0643, 0xFED9); dual(0x0644, 0xFEDD)
        dual(0x0645, 0xFEE1); dual(0x0646, 0xFEE5); dual(0x0647, 0xFEE9)
        right(0x0648, 0xFEED); right(0x0649, 0xFEEF)
        dual(0x064A, 0xFEF1)
        // Persian / Urdu extras that are commonly mixed into Arabic text
        dual(0x067E, 0xFB56); dual(0x0686, 0xFB7A); right(0x0698, 0xFB8A); dual(0x06A9, 0xFB8E)
        dual(0x06AF, 0xFB92); dual(0x06CC, 0xFBFC)
    }

    /** Lam-Alef ligatures: alef variant -> (isolated, final) */
    private val lamAlef = mapOf(
        '\u0622' to (0xFEF5 to 0xFEF6),
        '\u0623' to (0xFEF7 to 0xFEF8),
        '\u0625' to (0xFEF9 to 0xFEFA),
        '\u0627' to (0xFEFB to 0xFEFC)
    )

    private fun isTashkeel(c: Char) = c in '\u064B'..'\u065F' || c == '\u0670'

    private fun isArabicLetterBlock(c: Char) =
        c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

    /** Arabic-Indic digits are numbers, they behave like Latin digits (left-to-right runs) */
    private fun isArabicDigit(c: Char) = c in '\u0660'..'\u0669' || c in '\u06F0'..'\u06F9'

    @JvmStatic
    fun containsArabic(text: String): Boolean {
        for (c in text) if (isArabicLetterBlock(c) && !isArabicDigit(c)) return true
        return false
    }

    /** Entry point: shape and visually reorder [text]. Returns the input instance if it holds no Arabic. */
    @JvmStatic
    fun process(text: String): String {
        if (!containsArabic(text)) return text
        if (text.indexOf('\n') < 0) return reorder(shape(text))
        return text.split('\n').joinToString("\n") { if (containsArabic(it)) reorder(shape(it)) else it }
    }

    // ---------------------------------------------------------------- shaping

    private fun canConnectToNext(c: Char): Boolean {
        val f = forms[c] ?: return false
        return f[2] != 0 // has an initial form => dual joining
    }

    private fun canConnectToPrev(c: Char): Boolean {
        val f = forms[c] ?: return false
        return f[1] != 0
    }

    private fun shape(input: String): String {
        val s = buildString(input.length) { for (c in input) if (!isTashkeel(c)) append(c) }
        val sb = StringBuilder(s.length)
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
                val lig = lamAlef[s[i + 1]]
                if (lig != null) {
                    sb.append((if (prevJoins) lig.second else lig.first).toChar())
                    i += 2
                    continue
                }
            }
            val nextJoins = f[2] != 0 && i + 1 < s.length && canConnectToPrev(s[i + 1])
            val code = when {
                prevJoins && nextJoins -> f[3]
                prevJoins -> f[1]
                nextJoins -> f[2]
                else -> f[0]
            }
            sb.append(if (code != 0) code.toChar() else f[0].toChar())
            i++
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- bidi

    private const val R = 0
    private const val L = 1
    private const val N = 2

    private fun isStrongRtl(c: Char) = isArabicLetterBlock(c) && !isArabicDigit(c)

    private fun isNeutral(c: Char): Boolean {
        if (c.isWhitespace()) return true
        if (c.isLetterOrDigit()) return false
        // Private use / surrogates / symbols are used for Unciv icons and emoji: treat as left-to-right
        if (c in '\uE000'..'\uF8FF' || Character.isSurrogate(c)) return false
        return when (c) {
            '.', ',', ':', ';', '!', '?', '(', ')', '[', ']', '{', '}', '<', '>', '/', '\\', '-', '+', '%',
            '\'', '"', '\u060C', '\u061B', '\u061F', '\u00AB', '\u00BB', '*', '=', '&', '#', '@', '~', '|', '_',
            '\u2013', '\u2014', '\u2026', '\u201C', '\u201D', '\u2018', '\u2019' -> true
            else -> !(Character.getType(c).toByte().let {
                it == Character.MATH_SYMBOL || it == Character.OTHER_SYMBOL || it == Character.CURRENCY_SYMBOL || it == Character.MODIFIER_SYMBOL
            })
        }
    }

    private fun mirror(c: Char) = when (c) {
        '(' -> ')'; ')' -> '('
        '[' -> ']'; ']' -> '['
        '{' -> '}'; '}' -> '{'
        '<' -> '>'; '>' -> '<'
        '\u00AB' -> '\u00BB'; '\u00BB' -> '\u00AB'
        else -> c
    }

    private fun reorder(s: String): String {
        val n = s.length
        val type = IntArray(n)
        for (i in 0 until n) {
            val c = s[i]
            type[i] = when {
                isStrongRtl(c) -> R
                isNeutral(c) -> N
                else -> L
            }
        }
        // Signs and separators that belong to a number stay with it
        for (i in 0 until n) {
            if (type[i] != N) continue
            val c = s[i]
            val prevDigit = i > 0 && (s[i - 1].isDigit() || isArabicDigit(s[i - 1]))
            val nextDigit = i + 1 < n && (s[i + 1].isDigit() || isArabicDigit(s[i + 1]))
            when (c) {
                '+', '-' -> if (nextDigit) type[i] = L
                '.', ',', ':' -> if (nextDigit && prevDigit) type[i] = L
                '%' -> if (prevDigit) type[i] = L
                '/' -> if (prevDigit && nextDigit) type[i] = L
            }
        }
        // Resolve neutrals: between equal directions they take that direction, otherwise the base direction (R)
        var i = 0
        while (i < n) {
            if (type[i] != N) { i++; continue }
            var j = i
            while (j < n && type[j] == N) j++
            val left = if (i > 0) type[i - 1] else R
            val right = if (j < n) type[j] else R
            val resolved = if (left == right) left else R
            for (k in i until j) type[k] = resolved
            i = j
        }
        // Split into runs, reverse run order; reverse inside R runs, keep L runs
        val runs = ArrayList<IntArray>() // start, end, direction
        i = 0
        while (i < n) {
            var j = i
            while (j < n && type[j] == type[i]) j++
            runs += intArrayOf(i, j, type[i])
            i = j
        }
        val sb = StringBuilder(n)
        for (k in runs.indices.reversed()) {
            val (start, end, dir) = runs[k]
            if (dir == R) {
                for (p in end - 1 downTo start) sb.append(mirror(s[p]))
            } else {
                sb.append(s, start, end)
            }
        }
        return sb.toString()
    }

}
