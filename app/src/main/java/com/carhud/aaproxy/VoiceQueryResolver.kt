package com.carhud.aaproxy

import java.text.Normalizer
import kotlin.math.max

/**
 * Pure scoring/normalization layer for Vietnamese speech results.
 *
 * SpeechRecognizer can return several hypotheses. The first item is not always
 * the best one for Vietnamese names, artists or channel acronyms, so rank all
 * candidates using confidence + context instead of blindly taking index 0.
 */
internal object VoiceQueryResolver {

    data class Resolved(
        val text: String,
        val score: Float,
        val confidence: Float,
        val sourceIndex: Int
    )

    private val mediaPrefix = Regex(
        """^(?:(?:mở|bật|phát|nghe|tìm|tìm kiếm|cho nghe|cho tôi nghe|play|search for|listen to)\s+)+(?:bài hát|bài|nhạc|video|clip|song|music\s+)?""",
        RegexOption.IGNORE_CASE
    )

    private val channelCue = Regex(
        """\b(kênh|truyền hình|tivi|ti vi|tv|vtv|htv|htvc|vtc|thvl|k\+)\b""",
        RegexOption.IGNORE_CASE
    )

    private val spokenChannelReplacements = listOf(
        Regex("""\b(?:vê\s+tê\s+vê|v\s+t\s+v)\b""", RegexOption.IGNORE_CASE) to "vtv",
        Regex("""\b(?:hát\s+tê\s+vê|h\s+t\s+v)\b""", RegexOption.IGNORE_CASE) to "htv",
        Regex("""\b(?:vê\s+tê\s+xê|v\s+t\s+c)\b""", RegexOption.IGNORE_CASE) to "vtc",
        Regex("""\btruyền\s+hình\s+vĩnh\s+long\b""", RegexOption.IGNORE_CASE) to "thvl",
        Regex("""\b(?:ca|k)\s+cộng\b""", RegexOption.IGNORE_CASE) to "k+"
    )

    private val numberWords = linkedMapOf(
        "mười hai" to "12",
        "mười một" to "11",
        "mười" to "10",
        "một" to "1",
        "hai" to "2",
        "ba" to "3",
        "bốn" to "4",
        "tư" to "4",
        "năm" to "5",
        "sáu" to "6",
        "bảy" to "7",
        "tám" to "8",
        "chín" to "9"
    )

    fun resolve(
        hypotheses: List<String>,
        confidences: FloatArray?,
        activeAppId: String,
        channelNames: List<String>
    ): Resolved? {
        val clean = hypotheses.mapIndexedNotNull { index, raw ->
            val text = raw.trim().replace(Regex("""\s+"""), " ")
            if (text.isBlank()) null else index to text
        }
        if (clean.isEmpty()) return null

        val active = activeAppId.lowercase()
        var best: Resolved? = null

        for ((index, text) in clean) {
            val confidence = confidences?.getOrNull(index)?.takeIf { it in 0f..1f } ?: -1f
            var score = if (confidence >= 0f) confidence * 100f else 58f - index * 4f
            score += max(0f, 8f - index * 1.5f)

            val tv = TvVoiceHelper.parse(text)
            if (tv != null) {
                score += if (tv.channelTarget.isNotBlank()) 34f else 15f
                if (active == "iptv") score += 12f
            }

            val shouldScoreChannels = active == "iptv" || channelCue.containsMatchIn(text)
            val channelMatch = if (shouldScoreChannels) bestChannelMatch(text, channelNames) else null
            if (channelMatch != null) {
                score += 16f + channelMatch.second * 24f
                if (active == "iptv" || channelCue.containsMatchIn(text)) score += 10f
            }

            if (active == "youtube") {
                if (looksLikeMediaRequest(text)) score += 5f
                if (!channelCue.containsMatchIn(text)) score += 2f
            } else if (active == "web" || active.startsWith("custom_") || active == "maps") {
                if (looksLikeUrlOrSearch(text)) score += 4f
            }

            if (text.length <= 1) score -= 25f

            val candidate = Resolved(text, score, confidence, index)
            if (best == null || candidate.score > best!!.score) best = candidate
        }

        return best
    }

    fun cleanForMediaSearch(raw: String): String {
        val compact = raw.trim().replace(Regex("""\s+"""), " ")
        val stripped = compact.replace(mediaPrefix, "").trim()
        return stripped.ifBlank { compact }
    }

    fun canonicalizeSpokenChannel(raw: String): String {
        var text = raw.trim().lowercase()
        spokenChannelReplacements.forEach { (regex, replacement) ->
            text = text.replace(regex, replacement)
        }
        for ((word, digit) in numberWords) {
            text = text.replace(
                Regex("""\b(vtv|htv|htvc|vtc|thvl|k\+)\s+$word\b""", RegexOption.IGNORE_CASE)
            ) { m -> m.groupValues[1] + digit }
        }
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    fun bestChannelName(raw: String, channelNames: List<String>, minScore: Float = 0.76f): String? {
        val match = bestChannelMatch(raw, channelNames) ?: return null
        return match.first.takeIf { match.second >= minScore }
    }

    fun buildBiasStrings(
        activeAppId: String,
        channelNames: List<String>,
        appNames: List<String>
    ): ArrayList<String> {
        val active = activeAppId.lowercase()
        val out = LinkedHashSet<String>()

        listOf(
            "VTV1", "VTV2", "VTV3", "VTV4", "VTV5", "VTV6", "VTV7", "VTV8", "VTV9",
            "HTV7", "HTV9", "VTC1", "THVL1", "K+ SPORT 1", "K+ SPORT 2",
            "mở kênh", "phát", "nghe", "tìm kiếm", "YouTube", "IPTV"
        ).forEach(out::add)

        val channelLimit = if (active == "iptv") 64 else 28
        channelNames.asSequence()
            .map { it.trim() }
            .filter { it.length in 2..80 }
            .distinct()
            .take(channelLimit)
            .forEach(out::add)

        appNames.asSequence()
            .map { it.trim() }
            .filter { it.length in 2..60 }
            .distinct()
            .take(20)
            .forEach(out::add)

        return ArrayList(out.take(96))
    }

    private fun looksLikeMediaRequest(text: String): Boolean {
        val folded = fold(text)
        return folded.startsWith("mo ") ||
            folded.startsWith("bat ") ||
            folded.startsWith("phat ") ||
            folded.startsWith("nghe ") ||
            folded.startsWith("tim ") ||
            folded.contains("bai hat") ||
            folded.contains("nhac")
    }

    private fun looksLikeUrlOrSearch(text: String): Boolean {
        val t = text.lowercase()
        return t.contains(".com") || t.contains(".vn") || t.contains("www") || t.length >= 3
    }

    private fun bestChannelMatch(raw: String, channelNames: List<String>): Pair<String, Float>? {
        if (channelNames.isEmpty()) return null

        var spoken = stripChannelCommand(canonicalizeSpokenChannel(raw))
        for ((word, digit) in numberWords) {
            spoken = spoken.replace(
                Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE),
                digit
            )
        }

        val query = channelKey(spoken)
        if (query.isBlank()) return null

        var bestName: String? = null
        var bestScore = 0f
        for (name in channelNames) {
            val key = channelKey(name)
            if (key.isBlank()) continue
            val score = similarity(query, key)
            if (score > bestScore) {
                bestScore = score
                bestName = name
            }
        }
        return bestName?.let { it to bestScore }
    }

    private fun stripChannelCommand(raw: String): String {
        return raw
            .replace(
                Regex(
                    """^(?:(?:mở|bật|xem|phát|chuyển(?:\s+sang)?|chiếu)\s+)?(?:(?:kênh|truyền\s+hình)\s+)?""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("""\s+(?:hd|sd|fhd|4k)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun channelKey(raw: String): String {
        return fold(raw)
            .replace(Regex("""\s*(?:full hd|fhd|hd|sd|4k)$"""), "")
            .replace(Regex("""[^a-z0-9+]"""), "")
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isBlank() || b.isBlank()) return 0f
        if (a.contains(b) || b.contains(a)) {
            val shorter = minOf(a.length, b.length).toFloat()
            val longer = maxOf(a.length, b.length).toFloat()
            return 0.88f + 0.12f * (shorter / longer)
        }
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / maxOf(a.length, b.length).toFloat()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)

        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(
                    cur[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            val swap = prev
            prev = cur
            cur = swap
        }

        return prev[b.length]
    }

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
        .lowercase()
        .replace(Regex("""\s+"""), " ")
        .trim()
}
