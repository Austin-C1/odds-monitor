package com.wrbug.polymarketbot.service.autobetting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AdsPowerLocalApiServiceSourceTest {
    private val source = Files.readString(
        Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/autobetting/AdsPowerLocalApiService.kt")
    )

    @Test
    fun `crown placement verifies my bets count and receipt after accepted order`() {
        assertTrue(source.contains("beforeWagerCount"))
        assertTrue(source.contains("currentWagerCount > beforeWagerCount"))
        assertTrue(source.contains("extractReceiptReference"))
        assertTrue(source.contains("wager_count"))
        assertTrue(source.contains("pc_wager_count"))
    }

    @Test
    fun `crown placement automatically accepts confirmation prompts`() {
        assertTrue(source.contains("confirmBetIfPrompted"))
        assertTrue(source.contains("yes_btn"))
        assertTrue(source.contains("C_yes_btn"))
        assertTrue(source.contains("confirm_chk"))
        assertTrue(source.contains("C_confirm_chk"))
    }

    @Test
    fun `crown target selection parses page hosts and avoids arbitrary first page fallback`() {
        val selectionBlock = source.substringAfter("private fun selectCrownTarget")
            .substringBefore("private fun readPageSnapshotViaCdp")
        val hostEqualsBlock = source.substringAfter("private fun String?.hostEquals")
            .substringBefore("private data class BrowserTarget")

        assertFalse(selectionBlock.contains("?: pageTargets.firstOrNull()"))
        assertTrue(hostEqualsBlock.contains("hostFromUrl(this)"))
    }

    @Test
    fun `crown session matching checks every crown page target instead of only the first one`() {
        val matchBlock = source.substringAfter("fun matchCrownSession")
            .substringBefore("private fun noMatchedCrownSession")

        assertTrue(source.contains("readCrownPageSnapshots"))
        assertTrue(matchBlock.contains("flatMap"))
        assertFalse(matchBlock.contains("val snapshot = active.debugPort?.let { readCrownPageSnapshot"))
    }
}
