package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextEncodingUtilsTest {
    @Test
    fun `repairs gbk-decoded utf8 mojibake`() {
        assertEquals("平博", TextEncodingUtils.repairMojibake("\u9A9E\u51B2\u5D25"))
        assertEquals("皇冠", TextEncodingUtils.repairMojibake("\u9428\u56E7\u555D"))
        assertEquals("英超", TextEncodingUtils.repairMojibake("\u947B\u8FAB\u79F4"))
        assertEquals("动水通过 / 合水通过", TextEncodingUtils.repairMojibake("鍔ㄦ按閫氳繃 / 鍚堟按閫氳繃"))
        assertEquals("赔率变动", TextEncodingUtils.repairMojibake("璧旂巼鍙樺姩"))
        assertEquals("盘口", TextEncodingUtils.repairMojibake("鐩樺彛"))
    }

    @Test
    fun `repairs latin1-decoded utf8 mojibake from alert records`() {
        assertEquals("赔率变动", TextEncodingUtils.repairMojibake("èµç\u008e\u0087å\u008f\u0098å\u008a¨"))
        assertEquals("赛前赔率变动", TextEncodingUtils.repairMojibake("èµ\u009bå\u0089\u008dèµç\u008e\u0087å\u008f\u0098å\u008a¨"))
        assertEquals("盘口：让球 主队 0.5/1", TextEncodingUtils.repairMojibake("ç\u009b\u0098å\u008f£ï¼\u009aè®©ç\u0090\u0083 ä¸»é\u0098\u009f 0.5/1"))
        assertEquals("皇冠：0.83 -> 0.85", TextEncodingUtils.repairMojibake("ç\u009a\u0087å\u0086\u00a0ï¼\u009a0.83 -> 0.85"))
    }

    @Test
    fun `keeps valid readable text unchanged`() {
        assertEquals("加拿大超级联赛", TextEncodingUtils.repairMojibake("加拿大超级联赛"))
        assertEquals("Polymarket", TextEncodingUtils.repairMojibake("Polymarket"))
    }
}
