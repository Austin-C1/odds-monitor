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
                guardIndex >= 0 && guardIndex < placementBlock.indexOf("message: 'crown_place_button_native_click_required'")
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
    fun `crown placement returns receipt details for immediate betting history write`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val parserBlock = placementBlock.substringAfter("const parseVerifiedBetRecord")
            .substringBefore("const verifiedOpenBetPayload")
        val gatewayBlock = source.substringAfter("private fun com.fasterxml.jackson.databind.JsonNode.toCrownBetPlacementResult")
            .substringBefore("private fun com.fasterxml.jackson.databind.JsonNode.crownOpenBetRecordOrNull")

        assertTrue(parserBlock.contains("1\\s*X\\s*2"))
        assertTrue(parserBlock.contains("moneyline"))
        assertTrue(parserBlock.contains("ticketReference"))
        assertTrue(parserBlock.contains("stakeAmount"))
        assertTrue(parserBlock.contains("estimatedWin"))
        assertTrue(placementBlock.contains("record: parseVerifiedBetRecord(receiptText, currentOdds)"))
        assertTrue(gatewayBlock.contains("verifiedRecord = path(\"record\").crownOpenBetRecordOrNull()"))
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
        assertFalse(selectionBlock.contains("mos077"))
        assertFalse(selectionBlock.contains("112.121."))
        assertFalse(selectionBlock.contains("134.159.80.63"))
        assertTrue(selectionBlock.contains("isUsableCrownPageCandidate"))
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
        assertTrue(placementBlock.contains("visibleStakeInputs"))
        assertTrue(placementBlock.contains("findMatchingBetSlipStakeInput"))
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
        assertTrue(placementBlock.contains("!input || seen.has(input) || !isVisible(input)"))
        assertTrue(placementBlock.contains("buttonDisabled"))
        assertTrue(placementBlock.contains("isVisible(button) && !buttonDisabled(button)"))
    }

    @Test
    fun `crown placement reports unapplied stake before treating place button as disabled`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
        val fillBlock = source.substringAfter("const fillStakeInput")
            .substringBefore("const waitFor")

        assertTrue(fillBlock.contains("const focusStakeInput"))
        assertTrue(fillBlock.contains("await focusStakeInput()"))
        assertFalse(fillBlock.contains("if (!keyboardVisible()) return stakeMatches();"))
        assertTrue(fillBlock.contains("return stakeAccepted();"))
        assertTrue(placementBlock.contains("const stakeFilled = await fillStakeInput(stakeInput, stake, stakeAcceptedBySlip);"))
        assertTrue(placementBlock.contains("message: 'crown_stake_input_not_applied'"))
        assertTrue(
            placementBlock.indexOf("message: 'crown_stake_input_not_applied'") <
                placementBlock.indexOf("message: 'crown_place_button_disabled'")
        )
    }

    @Test
    fun `crown placement never clicks add to bet slip as a place bet fallback`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("Add\\s+to\\s+Bet\\s+Slip"))
        assertFalse(placementBlock.contains("findElementById('add_total_bet')"))
        assertFalse(placementBlock.contains("clickElement(addButton)"))
        assertFalse(placementBlock.contains("message: 'crown_betslip_stake_input_missing'"))
        assertFalse(placementBlock.contains("message: 'crown_betslip_stake_input_not_applied'"))
    }

    @Test
    fun `crown placement uses stake input from matching bet slip selection`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("const stakeInputCandidates"))
        assertTrue(placementBlock.contains("const textMatchesExpectedSlip"))
        assertTrue(placementBlock.contains("const findMatchingBetSlipStakeInput"))
        assertTrue(placementBlock.contains("const stakeInput = await waitFor(() => findMatchingBetSlipStakeInput()"))
        assertTrue(
            placementBlock.indexOf("const findMatchingBetSlipStakeInput") <
                placementBlock.indexOf("const stakeInput = await waitFor(() => findMatchingBetSlipStakeInput()")
        )
    }

    @Test
    fun `crown placement verifies stake activated place button before skipping keypad entry`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val fillBlock = source.substringAfter("const fillStakeInput")
            .substringBefore("const waitFor")

        assertTrue(placementBlock.contains("const stakeAcceptedBySlip"))
        assertTrue(fillBlock.contains("const stakeAccepted = async () =>"))
        assertTrue(fillBlock.contains("if (!accepted || accepted()) return true"))
        assertTrue(placementBlock.contains("await fillStakeInput(stakeInput, stake, stakeAcceptedBySlip)"))
        assertFalse(placementBlock.contains("slipStakeInput"))
        assertFalse(placementBlock.contains("await fillStakeInput(slipStakeInput, stake, stakeAcceptedBySlip)"))
    }

    @Test
    fun `crown placement follows mobile keypad flow before requesting native place bet`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val fillBlock = source.substringAfter("const fillStakeInput")
            .substringBefore("const waitFor")
        val keyboardIndex = fillBlock.indexOf("if (keyboardVisible())")
        val directIndex = fillBlock.indexOf("applyStakeDirectly()")
        val nativeReadyIndex = placementBlock.indexOf("message: 'crown_place_button_native_click_required'")

        assertTrue(fillBlock.contains("const keyboardVisible = () =>"))
        assertTrue(fillBlock.contains("clickElement(numberButton);"))
        assertFalse(fillBlock.contains("const doneButton"))
        assertFalse(fillBlock.contains("clickElement(doneButton)"))
        assertTrue(keyboardIndex >= 0)
        assertTrue(directIndex < 0 || keyboardIndex < directIndex)
        assertTrue(nativeReadyIndex > placementBlock.indexOf("const stakeFilled = await fillStakeInput"))
    }

    @Test
    fun `crown placement accepts stake after crown rerenders the stake input`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val acceptedBlock = placementBlock.substringAfter("const stakeAcceptedBySlip")
            .substringBefore("const stakeFilled")

        assertFalse(acceptedBlock.contains("candidate.input === stakeInput"))
        assertTrue(acceptedBlock.contains("readStakeNumber(candidate.input) === expectedStakeAmount"))
        assertTrue(acceptedBlock.contains("const exactStakeCandidates"))
        assertTrue(acceptedBlock.contains("const filledExpectedSelection"))
        assertTrue(acceptedBlock.contains("if (!filledExpectedSelection) return false"))
    }

    @Test
    fun `crown placement accepts exact stake from the only visible quick slip input`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val acceptedBlock = placementBlock.substringAfter("const stakeAcceptedBySlip")
            .substringBefore("const stakeFilled")

        assertTrue(acceptedBlock.contains("const exactStakeCandidates = candidates.filter"))
        assertTrue(acceptedBlock.contains("const filledExpectedSelection = exactStakeCandidates.find((candidate) => candidate.matchesExpected)"))
        assertTrue(acceptedBlock.contains("|| (exactStakeCandidates.length === 1 ? exactStakeCandidates[0] : null);"))
        assertFalse(acceptedBlock.contains("candidate.matchesExpected && readStakeNumber(candidate.input) === expectedStakeAmount"))
    }

    @Test
    fun `crown placement waits ten seconds after stake input before requesting native place bet`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val afterStakeFilledBlock = placementBlock.substringAfter("const stakeFilled = await fillStakeInput")
            .substringBefore("const finalPlaceButton = findPlaceBetButton() || orderButton")
        val stakeFilledIndex = placementBlock.indexOf("const stakeFilled = await fillStakeInput")
        val settleIndex = placementBlock.indexOf("await sleep(stakeSettleBeforePlaceBetMs);")
        val findButtonIndex = placementBlock.indexOf("let orderButton = await waitFor")
        val nativeReadyIndex = placementBlock.indexOf("message: 'crown_place_button_native_click_required'")

        assertTrue(placementBlock.contains("const stakeSettleBeforePlaceBetMs = 10000;"))
        assertTrue(afterStakeFilledBlock.contains("await sleep(stakeSettleBeforePlaceBetMs);"))
        assertTrue(stakeFilledIndex >= 0)
        assertTrue(settleIndex > stakeFilledIndex)
        assertTrue(findButtonIndex > settleIndex)
        assertTrue(nativeReadyIndex > findButtonIndex)
    }

    @Test
    fun `crown placement clicks visible place bet button instead of only fixed id`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("const findPlaceBetButton"))
        assertTrue(placementBlock.contains("PLACE\\s*BET"))
        assertTrue(placementBlock.contains("findElementById('order_bet')"))
        assertTrue(placementBlock.contains("buttonTextMatchesPlaceBet"))
    }

    @Test
    fun `crown placement clicks the place bet side when the action bar also contains add to bet slip`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val finderBlock = placementBlock.substringAfter("const findPlaceBetButton")
            .substringBefore("const placeClickAccepted")

        assertFalse(finderBlock.contains("return fixedButton;"))
        assertTrue(finderBlock.contains("mixedPlaceBetActionBar(fixedButton)"))
        assertTrue(placementBlock.contains("const placeBetClickPoint = (element) =>"))
        assertTrue(placementBlock.contains("rect.left + rect.width * 0.75"))
        assertTrue(placementBlock.contains("const nativePlaceButtonPoint = absoluteCenter(finalPlaceButton);"))
    }

    @Test
    fun `crown placement requests native cdp click as the primary final place bet action`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val afterButtonFoundBlock = placementBlock.substringAfter("let orderButton = await waitFor")
            .substringBefore("const historyButton")

        assertFalse(afterButtonFoundBlock.contains("await clickPlaceBetButton(orderButton)"))
        assertFalse(afterButtonFoundBlock.contains("const placeButtonClicked"))
        assertTrue(afterButtonFoundBlock.contains("const finalPlaceButton = findPlaceBetButton() || orderButton"))
        assertTrue(afterButtonFoundBlock.contains("const nativePlaceButtonPoint = absoluteCenter(finalPlaceButton);"))
        assertTrue(afterButtonFoundBlock.contains("message: 'crown_place_button_native_click_required'"))
    }

    @Test
    fun `crown placement uses native cdp mouse click for final place bet`() {
        val gatewayBlock = source.substringAfter("override fun placeBet")
            .substringBefore("override fun verifyPlacedBet")

        assertTrue(gatewayBlock.contains("dispatchNativePlaceBetClick(wsUrl, result)"))
        assertTrue(source.contains("crown_place_button_native_click_required"))
        assertTrue(source.contains("nativePlaceBetClickReasons"))
        assertTrue(source.contains("private fun dispatchCdpMouseClick"))
        assertTrue(source.contains("Input.dispatchMouseEvent"))
        assertTrue(source.contains("mousePressed"))
        assertTrue(source.contains("mouseReleased"))
        assertTrue(source.contains("const nativePlaceButtonPoint = absoluteCenter(finalPlaceButton)"))
        assertTrue(source.contains("placeButton: nativePlaceButtonPoint"))
    }

    @Test
    fun `crown placement does not try page click before native cdp click`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val nativeReadyIndex = placementBlock.indexOf("message: 'crown_place_button_native_click_required'")
        val clickFailedIndex = placementBlock.indexOf("message: 'crown_place_button_click_failed'")

        assertTrue(placementBlock.contains("const finalPlaceButton = findPlaceBetButton() || orderButton"))
        assertTrue(placementBlock.contains("placeButton: nativePlaceButtonPoint"))
        assertTrue(nativeReadyIndex >= 0)
        assertFalse(placementBlock.contains("await clickPlaceBetButton(orderButton);"))
        assertTrue(clickFailedIndex < 0 || nativeReadyIndex < clickFailedIndex)
    }

    @Test
    fun `crown native click verification closes receipt and returns to soccer page`() {
        val verificationBlock = source.substringAfter("private fun crownBetHistoryVerificationScript")
            .substringBefore("private fun buildUrl")

        assertTrue(verificationBlock.contains("const result = async"))
        assertTrue(verificationBlock.contains("await confirmBetIfPrompted();"))
        assertTrue(verificationBlock.contains("receiptOkButton = await receiptOkButtonPoint();"))
        assertTrue(verificationBlock.contains("receiptOkButton"))
        assertTrue(verificationBlock.contains("clickElement(historyButton)"))
    }

    @Test
    fun `crown native click verification returns ok button point instead of dom clicking receipt ok`() {
        val verificationBlock = source.substringAfter("private fun crownBetHistoryVerificationScript")
            .substringBefore("private fun buildUrl")
        val okBlock = verificationBlock.substringAfter("const receiptOkButtonPoint")
            .substringBefore("const normalizeLine")

        assertTrue(verificationBlock.contains("const nativePoint = (element) =>"))
        assertTrue(okBlock.contains("nativePoint(okButton)"))
        assertFalse(okBlock.contains("clickElement(okButton)"))
        assertTrue(verificationBlock.contains("receiptOkButton: receiptOkButton"))
    }

    @Test
    fun `crown placement dispatches native receipt ok click after verification`() {
        val placeBetBlock = source.substringAfter("override fun placeBet")
            .substringBefore("override fun verifyPlacedBet")
        val verifyBlock = source.substringAfter("override fun verifyPlacedBet")
            .substringBefore("private fun waitForCrownPageSnapshot")

        assertTrue(source.contains("private fun dispatchNativeReceiptOkClick"))
        assertTrue(placeBetBlock.contains("dispatchNativeReceiptOkClick(wsUrl, nativeResult)"))
        assertTrue(placeBetBlock.contains("dispatchNativeReceiptOkClick(wsUrl, result)"))
        assertTrue(verifyBlock.contains("dispatchNativeReceiptOkClick(wsUrl, result)"))
    }

    @Test
    fun `crown placement closes closed selections instead of leaving them in bet slip`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")

        assertTrue(placementBlock.contains("closedBettingPattern"))
        assertTrue(placementBlock.contains("currently\\s+closed\\s+for\\s+betting"))
        assertTrue(placementBlock.contains("closeClosedBetSlipSelection"))
        assertTrue(placementBlock.contains("closeExpectedBetSlipSelection"))
        assertTrue(placementBlock.contains("message: 'crown_selection_closed'"))
    }

    @Test
    fun `crown placement removes the selected slip before returning failures after market click`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("private fun buildUrl")
        val afterMarketClickBlock = placementBlock.substringAfter("clickElement(betElement);")
            .substringBefore("const historyButton")

        assertTrue(placementBlock.contains("const failAfterSelection = async (payload) =>"))
        assertTrue(placementBlock.contains("await closeExpectedBetSlipSelection();"))
        listOf(
            "crown_betslip_full",
            "crown_stake_input_missing",
            "crown_stake_input_not_applied",
            "crown_stake_below_minimum",
            "crown_stake_above_maximum",
            "crown_place_button_disabled",
            "crown_place_button_click_failed",
            "crown_bet_not_confirmed"
        ).forEach { reason ->
            assertTrue(
                Regex("return await failAfterSelection\\(\\{[\\s\\S]*message: '$reason'")
                    .containsMatchIn(afterMarketClickBlock),
                "Expected $reason to clean up the selected bet slip before returning"
            )
        }
        assertFalse(
            afterMarketClickBlock.contains(
                "return finish({ placed: false, historyVerified: false, message: 'crown_stake_input_not_applied'"
            )
        )
    }

    @Test
    fun `crown placement stays on soccer page before selecting a market`() {
        val placementBlock = source.substringAfter("private fun crownBetExecutionScript")
            .substringBefore("const betElement = await openTargetSoccerPage()")

        assertFalse(placementBlock.contains("const visibleBetSlipDeleteButtons"))
        assertFalse(placementBlock.contains("const openBetSlipPanel"))
        assertFalse(placementBlock.contains("const clearExistingSlip"))
        assertFalse(placementBlock.contains("const slipCleared = await clearExistingSlip();"))
        assertFalse(placementBlock.contains("message: 'crown_betslip_not_cleared'"))
    }

    @Test
    fun `crown native click verification confirms order before returning verified success`() {
        val verificationBlock = source.substringAfter("private fun crownBetHistoryVerificationScript")
            .substringBefore("private fun buildUrl")

        val resultDefinitionIndex = verificationBlock.indexOf("const result = async")
        val confirmIndex = verificationBlock.indexOf("await confirmBetIfPrompted();", resultDefinitionIndex)
        val receiptIndex = verificationBlock.indexOf("if (historyVerified(initial)) return await result(true, initial);")

        assertTrue(resultDefinitionIndex >= 0)
        assertTrue(confirmIndex >= 0)
        assertTrue(receiptIndex > confirmIndex)
        assertTrue(verificationBlock.contains("message: verified ? 'crown_history_verified' : 'crown_history_unverified'"))
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
    fun `crown placement cdp timeout includes the ten second stake cooldown`() {
        val evaluationBlock = source.substringAfter("private fun evaluateCrownPageJson")
            .substringBefore("private fun executeCdpCommand")

        assertTrue(source.contains("CROWN_BET_PLACEMENT_TIMEOUT_SECONDS = 45L"))
        assertTrue(evaluationBlock.contains("timeoutSeconds = CROWN_BET_PLACEMENT_TIMEOUT_SECONDS"))
    }
}
