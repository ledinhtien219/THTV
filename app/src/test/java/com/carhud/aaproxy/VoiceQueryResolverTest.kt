package com.carhud.aaproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueryResolverTest {

    @Test
    fun confidenceAndTvContextCanBeatFirstHypothesis() {
        val resolved = VoiceQueryResolver.resolve(
            hypotheses = listOf(
                "mở vê tê vê bảy",
                "mở kênh vtv3",
                "mở kênh vtv ba"
            ),
            confidences = floatArrayOf(0.44f, 0.91f, 0.75f),
            activeAppId = "iptv",
            channelNames = listOf("VTV3 HD", "HTV7 HD")
        )

        assertEquals("mở kênh vtv3", resolved?.text)
    }

    @Test
    fun fuzzyCustomChannelReturnsExactPlaylistName() {
        val channel = VoiceQueryResolver.bestChannelName(
            raw = "mở kênh hà nội một",
            channelNames = listOf("Hà Nội 1 HD", "VTV3 HD"),
            minScore = 0.70f
        )
        assertEquals("Hà Nội 1 HD", channel)
    }

    @Test
    fun mediaCommandIsCleanedBeforeYoutubeSearch() {
        assertEquals(
            "Hai Triệu Năm Đen Vâu",
            VoiceQueryResolver.cleanForMediaSearch("phát bài hát Hai Triệu Năm Đen Vâu")
        )
    }

    @Test
    fun biasContainsCommonAndImportedChannels() {
        val bias = VoiceQueryResolver.buildBiasStrings(
            activeAppId = "iptv",
            channelNames = listOf("Hà Nội 1 HD", "VTV3 HD"),
            appNames = listOf("YouTube Pro", "IPTV Truyền hình")
        )
        assertTrue("VTV3" in bias)
        assertTrue("Hà Nội 1 HD" in bias)
        assertTrue("YouTube Pro" in bias)
    }

    @Test
    fun spokenChannelCanonicalizationHandlesVietnameseLettersAndNumbers() {
        assertEquals(
            "mở kênh vtv3",
            VoiceQueryResolver.canonicalizeSpokenChannel("mở kênh vê tê vê ba")
        )
        assertEquals(
            "mở kênh vtc1",
            VoiceQueryResolver.canonicalizeSpokenChannel("mở kênh vê tê xê một")
        )
    }
}
