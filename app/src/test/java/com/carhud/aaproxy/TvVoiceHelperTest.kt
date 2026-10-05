package com.carhud.aaproxy

import org.junit.Assert.*
import org.junit.Test

class TvVoiceHelperTest {
    @Test fun recognizesVietnameseChannelNumbersAndSpelledLetters() {
        assertEquals("VTV3", TvVoiceHelper.parse("mở kênh vê tê vê ba")?.channelTarget)
        assertEquals("HTV7", TvVoiceHelper.parse("xem h t v bảy")?.channelTarget)
        assertEquals("VTV1", TvVoiceHelper.parse("mở truyền hình vtv một")?.channelTarget)
        assertEquals("VTV3", TvVoiceHelper.parse("vtv 3")?.channelTarget)
    }
    @Test fun musicAndGenericTvKeepTheirMeaning() {
        assertNull(TvVoiceHelper.parse("mở bài hát một nhà"))
        assertEquals("", TvVoiceHelper.parse("mở truyền hình")?.channelTarget)
    }
}
