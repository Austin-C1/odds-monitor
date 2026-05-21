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
    fun `crown placement disables native print dialogs during order confirmation`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("disableNativePrint"))
        assertTrue(placementBlock.contains("win.print"))
        assertTrue(placementBlock.contains("win.open"))
        assertTrue(
            placementBlock.indexOf("disableNativePrint();").let { guardIndex ->
                guardIndex >= 0 && guardIndex < placementBlock.indexOf("clickElement(orderButton)")
            }
        )
    }

    @Test
    fun `crown placement treats confirmed receipt reference as verified placement`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("receiptVerified"))
        assertTrue(placementBlock.contains("ticketReference && receiptVerified"))
        assertTrue(placementBlock.contains("message: 'crown_receipt_verified'"))
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

    @Test
    fun `crown page snapshot reads hidden account and balance fields`() {
        val snapshotBlock = source.substringAfter("private fun readPageSnapshotViaCdp")
            .substringBefore("private fun evaluateCrownPageJson")

        assertTrue(snapshotBlock.contains("acc_username"))
        assertTrue(snapshotBlock.contains("header_credit"))
        assertTrue(snapshotBlock.contains("userData"))
    }

    @Test
    fun `crown placement searches same origin frames for betting controls`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("accessibleWindows"))
        assertTrue(placementBlock.contains("findElementById(args.betElementId)"))
        assertTrue(placementBlock.contains("findSelector('input#bet_gold_pc')"))
        assertTrue(placementBlock.contains("ownerDocument?.defaultView"))
    }

    @Test
    fun `crown placement opens prematch page for prematch signals instead of always using live page`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("args.matchPhase"))
        assertTrue(placementBlock.contains("openTargetSoccerPage"))
        assertTrue(placementBlock.contains("old_ft_live_league"))
        assertTrue(placementBlock.contains("live_page"))
        assertTrue(placementBlock.contains("old_ft_league"))
        assertTrue(placementBlock.contains("today_page"))
    }

    @Test
    fun `crown placement rejects only when current odds are below configured minimum odds`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("const beforeWagerCount")

        assertFalse(placementBlock.contains("Math.abs(Number((currentOdds - Number(args.targetOdds)).toFixed(4)))"))
        assertFalse(placementBlock.contains("args.oddsTolerance"))
        assertTrue(placementBlock.contains("currentOdds < Number(args.targetOdds)"))
        assertTrue(placementBlock.contains("target_odds_below_minimum"))
    }

    @Test
    fun `crown placement cdp timeout covers receipt and history verification waits`() {
        val evaluationBlock = source.substringAfter("private fun evaluateCrownPageJson")
            .substringBefore("private fun executeCdpCommand")

        assertTrue(evaluationBlock.contains("timeoutSeconds = 75"))
    }
}
