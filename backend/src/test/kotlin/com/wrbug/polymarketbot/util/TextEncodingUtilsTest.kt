package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextEncodingUtilsTest {
    @Test
    fun `repairs gbk-decoded utf8 mojibake`() {
        assertEquals("平博", TextEncodingUtils.repairMojibake("\u9A9E\u51B2\u5D25"))
        assertEquals("皇冠", TextEncodingUtils.repairMojibake("\u9428\u56E7\u555D"))
        assertEquals("英超", TextEncodingUtils.repairMojibake("\u947B\u8FAB\u79F4"))
    }

    @Test
    fun `keeps valid readable text unchanged`() {
        assertEquals("加拿大超级联赛", TextEncodingUtils.repairMojibake("加拿大超级联赛"))
        assertEquals("Polymarket", TextEncodingUtils.repairMojibake("Polymarket"))
    }
}
