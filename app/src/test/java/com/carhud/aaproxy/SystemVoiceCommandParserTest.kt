package com.carhud.aaproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SystemVoiceCommandParserTest {
    @Test fun tvCommandsAndAppSuffix() {
        assertEquals(SystemVoiceCommand.Tv("VTV1"), SystemVoiceCommandParser.parse("mở kênh VTV1 trên THTV"))
        assertEquals(SystemVoiceCommand.Tv("HTV7"), SystemVoiceCommandParser.parse("Phát HTV 7 trên THTV Media"))
        assertEquals(SystemVoiceCommand.Tv("VTV10"), SystemVoiceCommandParser.parse("mở truyền hình VTV10"))
        assertEquals(SystemVoiceCommand.Tv("THVL1"), SystemVoiceCommandParser.parse("THVL1 HD"))
    }

    @Test fun spokenVietnameseChannelNumbers() {
        assertEquals(SystemVoiceCommand.Tv("VTV1"), SystemVoiceCommandParser.parse("mở vê tê vê một"))
        assertEquals(SystemVoiceCommand.Tv("HTV7"), SystemVoiceCommandParser.parse("phát hát tê vê bảy"))
        assertEquals(SystemVoiceCommand.Tv("VTV10"), SystemVoiceCommandParser.parse("VTV mười"))
    }

    @Test fun generalTvAndImportedChannel() {
        assertEquals(SystemVoiceCommand.Tv(""), SystemVoiceCommandParser.parse("mở truyền hình"))
        assertEquals(SystemVoiceCommand.Tv(""), SystemVoiceCommandParser.parse("bật ti vi"))
        assertEquals(SystemVoiceCommand.Tv("Hà Nội 1"), SystemVoiceCommandParser.parse("mở kênh Hà Nội 1"))
    }

    @Test fun musicCommandsKeepSongName() {
        assertEquals(SystemVoiceCommand.Music("Nắng ấm xa dần"), SystemVoiceCommandParser.parse("phát bài Nắng ấm xa dần trên THTV"))
        assertEquals(SystemVoiceCommand.Music("Sơn Tùng"), SystemVoiceCommandParser.parse("mở nhạc Sơn Tùng"))
        assertEquals(SystemVoiceCommand.Music("Em của ngày hôm qua"), SystemVoiceCommandParser.parse("Em của ngày hôm qua"))
        assertEquals(SystemVoiceCommand.Music(""), SystemVoiceCommandParser.parse("mở nhạc"))
    }

    @Test fun songMentioningAChannelIsNotTv() {
        assertEquals(SystemVoiceCommand.Music("hiệu VTV1"), SystemVoiceCommandParser.parse("nhạc hiệu VTV1"))
    }

    @Test fun assistantMetadataAndEmptyQueries() {
        assertEquals(SystemVoiceCommand.Resume, SystemVoiceCommandParser.parse(null))
        assertEquals(SystemVoiceCommand.Music("Mở cửa Sơn Tùng"), SystemVoiceCommandParser.parse(null, title = "Mở cửa", artist = "Sơn Tùng"))
        assertEquals(SystemVoiceCommand.Music("jazz"), SystemVoiceCommandParser.parse("", genre = "jazz"))
        assertEquals(SystemVoiceCommand.Music("Nhạc buổi sáng"), SystemVoiceCommandParser.parse(null, playlist = "Nhạc buổi sáng"))
        assertEquals(SystemVoiceCommand.Tv("VTV1"), SystemVoiceCommandParser.parse(null, title = "VTV1"))
    }

    @Test fun channelMatchingDoesNotConfuseVtv1WithVtv10() {
        assertEquals(SystemVoiceCommandParser.channelKey("VTV1"), SystemVoiceCommandParser.channelKey("VTV1 HD"))
        assertNotEquals(SystemVoiceCommandParser.channelKey("VTV1"), SystemVoiceCommandParser.channelKey("VTV10"))
        assertEquals(SystemVoiceCommandParser.channelKey("Hà Nội 1"), SystemVoiceCommandParser.channelKey("Ha Noi 1 HD"))
    }
}
