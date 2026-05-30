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
    fun `crown placement schedules page clicks outside the active cdp evaluation stack`() {
        val placementBlock = source.substringAfter("const clickElement")
            .substringBefore("const isVisible")

        assertTrue(placementBlock.contains("setTimeout(fireClick"))
    }

    @Test
    fun `crown placement disables native print dialogs during order confirmation`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val gatewayBlock = source.substringAfter("override fun placeBet")
            .substringBefore("private fun readCrownPageSnapshot")
        val printGuardBlock = source.substringAfter("private fun crownPrintGuardScript")
            .substringBefore("private fun installCrownPrintGuard")

        assertTrue(placementBlock.contains("disableNativePrint"))
        assertTrue(placementBlock.contains("win.print"))
        assertTrue(placementBlock.contains("win.open"))
        assertTrue(printGuardBlock.contains("!targetUrl"))
        assertTrue(gatewayBlock.contains("closeCrownPrintTargets(debugPort)"))
        assertTrue(gatewayBlock.contains("installCrownPrintGuard(initialWsUrl)"))
        assertTrue(gatewayBlock.contains("installCrownPrintGuard(wsUrl)"))
        assertTrue(
            placementBlock.indexOf("disableNativePrint();").let { guardIndex ->
                guardIndex >= 0 && guardIndex < placementBlock.indexOf("clickElement(orderButton)")
            }
        )
    }

    @Test
    fun `crown placement installs print guard into new documents before betting`() {
        val printGuardInstaller = source.substringAfter("private fun installCrownPrintGuard")
            .substringBefore("private fun closeCrownPrintTargets")
        val gatewayBlock = source.substringAfter("override fun placeBet")
            .substringBefore("private fun readCrownPageSnapshot")

        assertTrue(printGuardInstaller.contains("Page.addScriptToEvaluateOnNewDocument"))
        assertTrue(printGuardInstaller.contains("Runtime.evaluate"))
        assertTrue(printGuardInstaller.contains("crownPrintGuardScript()"))
        assertTrue(
            gatewayBlock.indexOf("installCrownPrintGuard(initialWsUrl)") in 0 until
                gatewayBlock.indexOf("activateCrownPageBeforePlacement")
        )
    }

    @Test
    fun `crown placement closes browser print targets before using account page`() {
        val targetBlock = source.substringAfter("private data class BrowserTarget")
            .substringBefore("private data class CrownPageSnapshot")
        val closeBlock = source.substringAfter("private fun closeCrownPrintTargets")
            .substringBefore("private fun readCrownPageSnapshot")
        val readTargetsBlock = source.substringAfter("private fun readCrownPageTargets")
            .substringBefore("private fun selectCrownTarget")

        assertTrue(targetBlock.contains("val id: String?"))
        assertTrue(readTargetsBlock.contains("id = node.path(\"id\")"))
        assertTrue(closeBlock.contains("/json/close/"))
        assertTrue(closeBlock.contains("chrome://print"))
        assertTrue(closeBlock.contains("printLike"))
    }

    @Test
    fun `crown placement checks session before betting without refreshing crown page`() {
        val gatewayBlock = source.substringAfter("override fun placeBet")
            .substringBefore("private fun readCrownPageSnapshot")
        val activationBlock = source.substringAfter("private fun activateCrownPageBeforePlacement")
            .substringBefore("private fun readCrownPageSnapshot")

        assertTrue(gatewayBlock.contains("activateCrownPageBeforePlacement(debugPort, target, command.loginUrl)"))
        assertTrue(
            gatewayBlock.indexOf("activateCrownPageBeforePlacement") <
                gatewayBlock.indexOf("crownBetExecutionScript(argsJson)")
        )
        assertFalse(activationBlock.contains("window.location.reload()"))
        assertFalse(source.contains("private fun reloadCrownPage"))
        assertTrue(activationBlock.contains("dismissCrownNetworkPrompt"))
        assertTrue(source.contains("crown_network_unstable"))
        assertTrue(source.contains("网络不稳定"))
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
    fun `crown placement closes successful receipt panel before returning success`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val closeReceiptIndex = placementBlock.indexOf("await closeSuccessfulReceiptPanel()")
        val receiptSuccessIndex = placementBlock.indexOf("message: 'crown_receipt_verified'")
        val historySuccessIndex = placementBlock.indexOf("message: 'crown_history_verified'", receiptSuccessIndex)

        assertTrue(placementBlock.contains("closeSuccessfulReceiptPanel"))
        assertTrue(placementBlock.contains("YOUR BETS HAVE BEEN SUCCESSFULLY PLACED"))
        assertTrue(placementBlock.contains("Retain Selection"))
        assertTrue(placementBlock.contains("text === 'OK'"))
        assertTrue(closeReceiptIndex >= 0 && closeReceiptIndex < receiptSuccessIndex)
        assertTrue(
            historySuccessIndex < 0 ||
                placementBlock.lastIndexOf("await closeSuccessfulReceiptPanel()", historySuccessIndex) >= receiptSuccessIndex
        )
    }

    @Test
    fun `crown placement treats matching open bets history as verified placement`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("openBetVerified"))
        assertTrue(placementBlock.contains("openBetMatchesExpectedMatch"))
        assertTrue(placementBlock.contains("expectedOpenBetSelection"))
        assertTrue(placementBlock.contains("market !== 'handicap'"))
        assertTrue(placementBlock.contains("selection === 'home'"))
        assertTrue(placementBlock.contains("selection === 'away'"))
        assertTrue(placementBlock.contains("Stake: ' + expectedStake"))
        assertTrue(placementBlock.contains("value.toLowerCase().includes(selectionText.toLowerCase())"))
        assertTrue(placementBlock.contains("verifiedOpenBetPayload"))
        assertTrue(placementBlock.contains("message: 'crown_history_verified'"))
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
        assertTrue(placementBlock.contains("visibleStakeInput("))
        assertTrue(placementBlock.contains("'input#bet_gold_pc'"))
        assertTrue(placementBlock.contains("ownerDocument?.defaultView"))
    }

    @Test
    fun `crown placement opens stake input before applying stake amount`() {
        val placementBlock = source.substringAfter("const fillStakeInput")
            .substringBefore("const waitFor")

        assertTrue(placementBlock.contains("const applyStakeDirectly"))
        assertTrue(
            placementBlock.indexOf("await focusStakeInput()").let { focusIndex ->
                val directIndex = placementBlock.indexOf("applyStakeDirectly()")
                focusIndex >= 0 && directIndex > focusIndex
            }
        )
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
    fun `crown placement reads current odds from visible odds text and fallback attributes`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("const beforeWagerCount")

        assertTrue(placementBlock.contains("readBetElementOdds"))
        assertTrue(placementBlock.contains("data-ior"))
        assertTrue(placementBlock.contains("text_odds"))
        assertTrue(placementBlock.contains("parseCrownOddsText"))
    }

    @Test
    fun `crown placement ignores hidden stake inputs and hidden order buttons`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")

        assertTrue(placementBlock.contains("const visibleStakeInput"))
        assertTrue(placementBlock.contains("input && isVisible(input)"))
        assertTrue(placementBlock.contains("button && !button.disabled && isVisible(button) ? button : null"))
    }

    @Test
    fun `crown placement reports unapplied stake before treating place button as disabled`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
        val fillBlock = source.substringAfter("const fillStakeInput")
            .substringBefore("const waitFor")

        assertTrue(fillBlock.contains("const focusStakeInput"))
        assertTrue(fillBlock.contains("await focusStakeInput()"))
        assertTrue(fillBlock.contains("return stakeMatches()"))
        assertTrue(placementBlock.contains("const stakeFilled = await fillStakeInput(stakeInput, stake);"))
        assertTrue(placementBlock.contains("message: 'crown_stake_input_not_applied'"))
        assertTrue(placementBlock.contains("message: 'crown_betslip_stake_input_not_applied'"))
        assertTrue(
            placementBlock.indexOf("message: 'crown_stake_input_not_applied'") <
                placementBlock.indexOf("message: 'crown_place_button_disabled'")
        )
    }

    @Test
    fun `crown placement opens and clears stale bet slip selections before selecting a market`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("const betElement = await openTargetSoccerPage()")

        assertTrue(placementBlock.contains("const openBetSlipPanel"))
        assertTrue(placementBlock.contains("const visibleBetSlipDeleteButtons"))
        assertTrue(placementBlock.contains("const slipCleared = await clearExistingSlip();"))
        assertTrue(placementBlock.contains("message: 'crown_betslip_not_cleared'"))
        assertTrue(
            placementBlock.indexOf("const slipCleared = await clearExistingSlip();") <
                source.indexOf("clickElement(betElement);")
        )
    }

    @Test
    fun `crown placement reports the crown ten-selection limit instead of timing out`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("const stakeInput = await waitFor")

        assertTrue(placementBlock.contains("const betSlipLimitReached"))
        assertTrue(placementBlock.contains("message: 'crown_betslip_full'"))
        assertTrue(
            placementBlock.indexOf("message: 'crown_betslip_full'") >
                placementBlock.indexOf("clickElement(betElement);")
        )
    }

    @Test
    fun `crown placement cdp timeout limits one account to thirty seconds`() {
        val evaluationBlock = source.substringAfter("private fun evaluateCrownPageJson")
            .substringBefore("private fun executeCdpCommand")

        assertTrue(source.contains("CROWN_BET_PLACEMENT_TIMEOUT_SECONDS = 30L"))
        assertTrue(evaluationBlock.contains("timeoutSeconds = CROWN_BET_PLACEMENT_TIMEOUT_SECONDS"))
    }
}
