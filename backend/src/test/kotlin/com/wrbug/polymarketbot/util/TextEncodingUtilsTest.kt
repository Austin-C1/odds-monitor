package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextEncodingUtilsTest {
    @Test
    fun `repairs gbk-decoded utf8 mojibake`() {
        assertEquals("平博", TextEncodingUtils.repairMojibake("骞冲崥"))
        assertEquals("皇冠", TextEncodingUtils.repairMojibake("鐨囧啝"))
        assertEquals("英超", TextEncodingUtils.repairMojibake("鑻辫秴"))
    }

    @Test
    fun `keeps valid readable text unchanged`() {
        assertEquals("加拿大超级联赛", TextEncodingUtils.repairMojibake("加拿大超级联赛"))
        assertEquals("Polymarket", TextEncodingUtils.repairMojibake("Polymarket"))
    }
}
