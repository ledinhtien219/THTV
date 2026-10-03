package com.carhud.aaproxy

import java.util.Locale

/**
 * High-speed Vietnamese Telex engine designed for in-car touchscreen typing.
 * Supports:
 * - Telex key combinations: aa->â, aw->ă, ee->ê, oo->ô, ow->ơ, uw->ư, dd->đ
 * - Tones: s (sắc), f (huyền), r (hỏi), x (ngã), j (nặng), z (xóa dấu)
 * - Direct tone application for quick accent bar buttons
 */
object VietnameseTelexEngine {

    private val VOWELS_RAW = "aăâeêioôơuưyAĂÂEÊIOÔƠUƯY"

    private val TONE_MAP = mapOf(
        'a' to arrayOf('a', 'á', 'à', 'ả', 'ã', 'ạ'),
        'ă' to arrayOf('ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'),
        'â' to arrayOf('â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'),
        'e' to arrayOf('e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'),
        'ê' to arrayOf('ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'),
        'i' to arrayOf('i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'),
        'o' to arrayOf('o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'),
        'ô' to arrayOf('ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'),
        'ơ' to arrayOf('ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'),
        'u' to arrayOf('u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'),
        'ư' to arrayOf('ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'),
        'y' to arrayOf('y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'),

        'A' to arrayOf('A', 'Á', 'À', 'Ả', 'Ã', 'Ạ'),
        'Ă' to arrayOf('Ă', 'Ắ', 'Ằ', 'Ẳ', 'Ẵ', 'Ặ'),
        'Â' to arrayOf('Â', 'Ấ', 'Ầ', 'Ẩ', 'Ẫ', 'Ậ'),
        'E' to arrayOf('E', 'É', 'È', 'Ẻ', 'Ẽ', 'Ẹ'),
        'Ê' to arrayOf('Ê', 'Ế', 'Ề', 'Ể', 'Ễ', 'Ệ'),
        'I' to arrayOf('I', 'Í', 'Ì', 'Ỉ', 'Ĩ', 'Ị'),
        'O' to arrayOf('O', 'Ó', 'Ò', 'Ỏ', 'Õ', 'Ọ'),
        'Ô' to arrayOf('Ô', 'Ố', 'Ồ', 'Ổ', 'Ỗ', 'Ộ'),
        'Ơ' to arrayOf('Ơ', 'Ớ', 'Ờ', 'Ở', 'Ỡ', 'Ợ'),
        'U' to arrayOf('U', 'Ú', 'Ù', 'Ủ', 'Ũ', 'Ụ'),
        'Ư' to arrayOf('Ư', 'Ứ', 'Ừ', 'Ử', 'Ữ', 'Ự'),
        'Y' to arrayOf('Y', 'Ý', 'Ỳ', 'Ỷ', 'Ỹ', 'Ỵ')
    )

    // Base char map from accented to unaccented base vowel
    private val BASE_VOWEL_MAP: Map<Char, Pair<Char, Int>> = buildMap {
        for ((base, forms) in TONE_MAP) {
            for (toneIdx in forms.indices) {
                put(forms[toneIdx], Pair(base, toneIdx))
            }
        }
    }

    /**
     * Tone indexes:
     * 0: Không dấu
     * 1: Sắc
     * 2: Huyền
     * 3: Hỏi
     * 4: Ngã
     * 5: Nặng
     */
    fun getToneIndex(ch: Char): Int = when (ch.lowercaseChar()) {
        's' -> 1
        'f' -> 2
        'r' -> 3
        'x' -> 4
        'j' -> 5
        'z' -> 0
        else -> -1
    }

    /**
     * Process full text + new character key press with Telex rules.
     * Returns the updated full text and new cursor position.
     */
    fun processKey(fullText: String, selStart: Int, selEnd: Int, newKey: String, telexEnabled: Boolean = true): Pair<String, Int> {
        val start = Math.min(selStart, selEnd).coerceAtLeast(0)
        val end = Math.max(selStart, selEnd).coerceAtMost(fullText.length)

        if (!telexEnabled || newKey.length != 1) {
            val res = fullText.substring(0, start) + newKey + fullText.substring(end)
            return Pair(res, start + newKey.length)
        }

        val keyChar = newKey[0]

        // Find boundary of word ending at `start`
        var wordStart = start
        while (wordStart > 0 && !fullText[wordStart - 1].isWhitespace() && fullText[wordStart - 1] !in ",.?!:;()[]{}\"'-/\\") {
            wordStart--
        }

        val currentWord = fullText.substring(wordStart, start)
        val transformed = transformWord(currentWord, keyChar)

        if (transformed != null) {
            val res = fullText.substring(0, wordStart) + transformed + fullText.substring(end)
            val newPos = wordStart + transformed.length
            return Pair(res, newPos)
        } else {
            val res = fullText.substring(0, start) + newKey + fullText.substring(end)
            return Pair(res, start + newKey.length)
        }
    }

    /**
     * Try transforming a single Vietnamese word with an incoming character.
     * Returns null if no Telex rule matched and it should just be appended.
     */
    fun transformWord(word: String, key: Char): String? {
        if (word.isEmpty()) {
            if (key == 'w' || key == 'W') return if (key == 'W') "Ư" else "ư"
            return null
        }

        val isUpper = key.isUpperCase()
        val lowerKey = key.lowercaseChar()
        val lastChar = word.last()
        val lowerLast = lastChar.lowercaseChar()

        // 1. dd -> đ
        if (lowerKey == 'd' && lowerLast == 'd') {
            val prefix = word.dropLast(1)
            val dChar = if (lastChar.isUpperCase()) 'Đ' else 'đ'
            return prefix + dChar
        }

        // 2. Vowel hats & horns
        if (lowerKey == 'a' && (lowerLast == 'a' || lowerLast == 'á' || lowerLast == 'à' || lowerLast == 'ả' || lowerLast == 'ã' || lowerLast == 'ạ')) {
            val (base, tone) = BASE_VOWEL_MAP[lastChar] ?: Pair('a', 0)
            val newBase = if (lastChar.isUpperCase()) 'Â' else 'â'
            val newChar = TONE_MAP[newBase]?.getOrNull(tone) ?: newBase
            return word.dropLast(1) + newChar
        }
        if (lowerKey == 'w' && (lowerLast == 'a' || lowerLast == 'á' || lowerLast == 'à' || lowerLast == 'ả' || lowerLast == 'ã' || lowerLast == 'ạ')) {
            val (base, tone) = BASE_VOWEL_MAP[lastChar] ?: Pair('a', 0)
            val newBase = if (lastChar.isUpperCase()) 'Ă' else 'ă'
            val newChar = TONE_MAP[newBase]?.getOrNull(tone) ?: newBase
            return word.dropLast(1) + newChar
        }
        if (lowerKey == 'e' && (lowerLast == 'e' || lowerLast == 'é' || lowerLast == 'è' || lowerLast == 'ẻ' || lowerLast == 'ẽ' || lowerLast == 'ẹ')) {
            val (base, tone) = BASE_VOWEL_MAP[lastChar] ?: Pair('e', 0)
            val newBase = if (lastChar.isUpperCase()) 'Ê' else 'ê'
            val newChar = TONE_MAP[newBase]?.getOrNull(tone) ?: newBase
            return word.dropLast(1) + newChar
        }
        if (lowerKey == 'o' && (lowerLast == 'o' || lowerLast == 'ó' || lowerLast == 'ò' || lowerLast == 'ỏ' || lowerLast == 'õ' || lowerLast == 'ọ')) {
            val (base, tone) = BASE_VOWEL_MAP[lastChar] ?: Pair('o', 0)
            val newBase = if (lastChar.isUpperCase()) 'Ô' else 'ô'
            val newChar = TONE_MAP[newBase]?.getOrNull(tone) ?: newBase
            return word.dropLast(1) + newChar
        }
        if (lowerKey == 'w' && (lowerLast == 'o' || lowerLast == 'ó' || lowerLast == 'ò' || lowerLast == 'ỏ' || lowerLast == 'õ' || lowerLast == 'ọ')) {
            val (base, tone) = BASE_VOWEL_MAP[lastChar] ?: Pair('o', 0)
            val newBase = if (lastChar.isUpperCase()) 'Ơ' else 'ơ'
            val newChar = TONE_MAP[newBase]?.getOrNull(tone) ?: newBase
            return word.dropLast(1) + newChar
        }
        if (lowerKey == 'w' && (lowerLast == 'u' || lowerLast == 'ú' || lowerLast == 'ù' || lowerLast == 'ủ' || lowerLast == 'ũ' || lowerLast == 'ụ')) {
            val (base, tone) = BASE_VOWEL_MAP[lastChar] ?: Pair('u', 0)
            val newBase = if (lastChar.isUpperCase()) 'Ư' else 'ư'
            val newChar = TONE_MAP[newBase]?.getOrNull(tone) ?: newBase
            return word.dropLast(1) + newChar
        }

        // 'w' shortcut: if word ends with 'uo' -> 'ươ'
        if (lowerKey == 'w' && word.length >= 2) {
            val secondLast = word[word.length - 2].lowercaseChar()
            if (secondLast == 'u' && lowerLast == 'o') {
                val uChar = if (word[word.length - 2].isUpperCase()) 'Ư' else 'ư'
                val oChar = if (lastChar.isUpperCase()) 'Ơ' else 'ơ'
                return word.dropLast(2) + uChar + oChar
            }
        }

        // 3. Tones: s, f, r, x, j, z
        val toneIdx = getToneIndex(key)
        if (toneIdx != -1) {
            val toneApplied = applyToneToWord(word, toneIdx)
            if (toneApplied != null) {
                return toneApplied
            }
        }

        return null
    }

    /**
     * Apply tone mark to the appropriate vowel in the word.
     */
    fun applyToneToWord(word: String, targetTone: Int): String? {
        val vowelIndices = mutableListOf<Int>()
        for (i in word.indices) {
            if (BASE_VOWEL_MAP.containsKey(word[i])) {
                vowelIndices.add(i)
            }
        }
        if (vowelIndices.isEmpty()) return null

        // Find which vowel index should receive the tone mark:
        val targetVowelIdx = findMainVowelIndex(word, vowelIndices)
        if (targetVowelIdx < 0) return null

        val chars = word.toCharArray()

        // Check current tone
        var currentTone = 0
        for (idx in vowelIndices) {
            val (_, tone) = BASE_VOWEL_MAP[chars[idx]] ?: Pair(' ', 0)
            if (tone > 0) {
                currentTone = tone
            }
            // Strip any existing tone from all vowels
            val (baseVowel, _) = BASE_VOWEL_MAP[chars[idx]] ?: Pair(chars[idx], 0)
            chars[idx] = baseVowel
        }

        // Toggle tone off if typed the same tone again
        val finalTone = if (currentTone == targetTone && targetTone != 0) 0 else targetTone

        val targetChar = chars[targetVowelIdx]
        val (base, _) = BASE_VOWEL_MAP[targetChar] ?: Pair(targetChar, 0)
        val withTone = TONE_MAP[base]?.getOrNull(finalTone) ?: base
        chars[targetVowelIdx] = withTone

        return String(chars)
    }

    private fun findMainVowelIndex(word: String, vowelIndices: List<Int>): Int {
        if (vowelIndices.size == 1) return vowelIndices[0]

        val lastVowelPos = vowelIndices.last()
        val hasEndingConsonant = lastVowelPos < word.length - 1

        // Check for special dipthongs: oa, oe, uy
        if (vowelIndices.size == 2) {
            val first = word[vowelIndices[0]].lowercaseChar()
            val second = word[vowelIndices[1]].lowercaseChar()
            if (!hasEndingConsonant) {
                if (first == 'o' && (second == 'a' || second == 'e')) return vowelIndices[1]
                if (first == 'u' && second == 'y') return vowelIndices[1]
            }
        }

        // If has ending consonant, tone belongs to the last vowel in cluster (e.g. án, ốc, oán, ường)
        if (hasEndingConsonant) {
            // Exceptions: ươ -> tone on ơ
            for (idx in vowelIndices) {
                val ch = word[idx].lowercaseChar()
                if (ch == 'ơ' || ch == 'ê' || ch == 'ô') return idx
            }
            return vowelIndices.last()
        }

        // No ending consonant: prioritize hat/horn vowels (ê, ô, ơ, ư, â, ă)
        for (idx in vowelIndices) {
            val ch = word[idx].lowercaseChar()
            if (ch == 'ê' || ch == 'ô' || ch == 'ơ' || ch == 'ư' || ch == 'â' || ch == 'ă') {
                return idx
            }
        }

        // Default to first or second depending on diphthong
        return vowelIndices[0]
    }
}
