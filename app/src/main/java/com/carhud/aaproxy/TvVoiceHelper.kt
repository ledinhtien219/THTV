package com.carhud.aaproxy

data class TvCommand(
    val isTvCommand: Boolean,
    val channelTarget: String // e.g. "VTV1", "VTV3", "VTV6", or "" for general TV
)

object TvVoiceHelper {
    // Matches expressions like:
    // "mở kênh vtv1", "mở vtv 3", "bật vtv6", "xem vtv1", "chuyển sang kênh htv7", "kênh vtv1 hd", "thvl1", "vtc1", etc.
    private val CHANNEL_PATTERN = Regex(
        """(?:(?:mở|bật|xem|chuyển|chiếu|phát)\s*(?:sang\s+|đến\s+)?)?(?:kênh\s+|truyền\s+hình\s+)?((?:vtv|htv|htvc|vtc|thvl|k\+)\s*[0-9a-zA-Z]+(?:\s*hd)?)""",
        RegexOption.IGNORE_CASE
    )

    private val GENERAL_TV_KEYWORDS = listOf(
        "mở truyền hình", "bật truyền hình", "xem truyền hình",
        "mở ti vi", "bật ti vi", "xem ti vi",
        "mở tivi", "bật tivi", "xem tivi",
        "mở tv", "bật tv", "xem tv",
        "truyền hình", "tivi", "ti vi"
    )

    fun parse(rawQuery: String): TvCommand? {
        val q = normalizeSpokenChannels(rawQuery.trim().lowercase())

        // 2. Specific channel pattern
        val match = CHANNEL_PATTERN.find(q)
        if (match != null) {
            val matchedGroup = match.groupValues[1].trim()
            val normalized = normalizeChannelName(matchedGroup)
            if (normalized.isNotBlank()) {
                return TvCommand(isTvCommand = true, channelTarget = normalized)
            }
        }

        // 3. Standalone channel name (e.g. "vtv1", "vtv 3", "htv7")
        val standaloneRegex = Regex("""^(?:kênh\s+)?(vtv\s*\d+|htv\s*\d+|thvl\s*\d+|vtc\s*\d+|k\+\s*\d+)$""", RegexOption.IGNORE_CASE)
        val standaloneMatch = standaloneRegex.find(q)
        if (standaloneMatch != null) {
            val group = standaloneMatch.groupValues[1].trim()
            val normalized = normalizeChannelName(group)
            return TvCommand(isTvCommand = true, channelTarget = normalized)
        }

        for (kw in GENERAL_TV_KEYWORDS) {
            if (q == kw || q.startsWith("$kw ") || q.endsWith(" $kw")) {
                return TvCommand(isTvCommand = true, channelTarget = "")
            }
        }
        return null
    }

    private fun normalizeSpokenChannels(query: String): String {
        var text = query
            .replace(Regex("""\b(?:vê\s+tê\s+vê|v\s+t\s+v)\b"""), "vtv")
            .replace(Regex("""\b(?:hát\s+tê\s+vê|h\s+t\s+v)\b"""), "htv")
            .replace(Regex("""\b(?:vê\s+tê\s+xê|v\s+t\s+c)\b"""), "vtc")
            .replace(Regex("""\btruyền\s+hình\s+vĩnh\s+long\b"""), "thvl")
            .replace(Regex("""\b(?:ca|k)\s+cộng\b"""), "k+")

        val digits = mapOf(
            "một" to "1", "hai" to "2", "ba" to "3", "bốn" to "4", "tư" to "4",
            "năm" to "5", "sáu" to "6", "bảy" to "7", "tám" to "8", "chín" to "9",
            "mười" to "10", "mười một" to "11", "mười hai" to "12"
        )

        text = text.replace(
            Regex("""\b(vtv|htv|htvc|vtc|thvl|k\+)\s+(mười\s+hai|mười\s+một|mười|một|hai|ba|bốn|tư|năm|sáu|bảy|tám|chín)\b""")
        ) {
            it.groupValues[1] + digits.getValue(it.groupValues[2])
        }
        return text
    }

    fun normalizeChannelName(raw: String): String {
        var s = raw.uppercase().trim()
        // Replace spaces between letters and numbers: "VTV 1" -> "VTV1", "HTV 7" -> "HTV7"
        s = s.replace(Regex("""(VTV|HTV|HTVC|VTC|THVL|K\+)\s+(\d+)"""), "$1$2")
        return s
    }
}
