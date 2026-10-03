package com.carhud.aaproxy

import java.text.Normalizer

internal sealed class SystemVoiceCommand {
    data class Music(val query: String) : SystemVoiceCommand()
    data class Tv(val channel: String) : SystemVoiceCommand()
    object Resume : SystemVoiceCommand()
}

internal object SystemVoiceCommandParser {
    private val spokenNumbers = mapOf("mot" to "1", "hai" to "2", "ba" to "3", "bon" to "4", "nam" to "5", "sau" to "6", "bay" to "7", "tam" to "8", "chin" to "9", "muoi" to "10", "muoi mot" to "11", "muoi hai" to "12")
    private val suffix = Regex("""\s+(?:trên|tren|bằng|bang|on|using)\s+t\s*h\s*t\s*v(?:\s+media)?\s*$""", RegexOption.IGNORE_CASE)
    private val channelPattern = Regex("""^(?:(?:mo|bat|xem|phat|chuyen(?: sang)?|play|watch)\s+)?(?:(?:kenh|truyen hinh)\s+)?(vtv|htvc|htv|vtc|thvl|k\+)\s*(\d{1,2}|muoi mot|muoi hai|mot|hai|ba|bon|nam|sau|bay|tam|chin|muoi)(?:\s*(?:hd|sd|fhd|4k))?$""")

    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace('Đ', 'D').lowercase()
        .replace(Regex("\\s+"), " ").trim()

    fun parse(query: String?, title: String? = null, artist: String? = null, genre: String? = null, playlist: String? = null): SystemVoiceCommand {
        val raw = query.orEmpty().trim().replace(suffix, "").trim()
        val namedTitle = title.orEmpty().trim()
        val candidate = raw.ifBlank { namedTitle }
        val normalized = fold(candidate).replace("ve te ve", "vtv").replace("hat te ve", "htv")
            .replace("ve te xe", "vtc").replace("truyen hinh vinh long", "thvl")
        channelPattern.matchEntire(normalized)?.let {
            val number = spokenNumbers[it.groupValues[2]] ?: it.groupValues[2]
            return SystemVoiceCommand.Tv(it.groupValues[1].uppercase() + number)
        }
        if (normalized.matches(Regex("""(?:(?:mo|bat|xem|phat|play|watch)\s+)?(?:truyen hinh|tivi|ti vi|tv)"""))) {
            return SystemVoiceCommand.Tv("")
        }
        // Explicit channel requests also support user-imported channel names.
        val customChannel = Regex("""^(?:(?:mở|mo|bật|bat|xem|phát|phat|chuyển sang|chuyen sang|play|watch)\s+)?(?:kênh|kenh|truyền hình|truyen hinh)\s+(.+)$""", RegexOption.IGNORE_CASE).find(candidate)
        if (customChannel != null) return SystemVoiceCommand.Tv(customChannel.groupValues[1].trim())

        val metadataQuery = listOf(namedTitle, artist.orEmpty().trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (metadataQuery.isNotBlank()) return SystemVoiceCommand.Music(metadataQuery)
        if (raw.isBlank()) {
            val fallback = playlist.orEmpty().ifBlank { genre.orEmpty() }.trim()
            return if (fallback.isBlank()) SystemVoiceCommand.Resume else SystemVoiceCommand.Music(fallback)
        }
        val musicQuery = raw.replace(Regex("""^(?:mở|mo|bật|bat|phát|phat|nghe|play|listen to)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:bài hát|bai hat|bản nhạc|ban nhac|bài|bai|nhạc|nhac|music|song)(?:\s+|$)""", RegexOption.IGNORE_CASE), "").trim()
        return SystemVoiceCommand.Music(musicQuery)
    }

    fun channelKey(name: String): String = fold(name).replace(Regex("""\s*(?:full hd|fhd|hd|sd|4k)$"""), "")
        .replace(Regex("""[^a-z0-9+]"""), "")
}
