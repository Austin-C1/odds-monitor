package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.dto.AutoBettingSignalRequest
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class AutoBettingDecisionServiceTest {
    private val repository = mock(AutoBettingIntentRepository::class.java)
    private val service = AutoBettingDecisionService(repository)

    @Test
    fun `odds monitor signal creates ready intent when crown price beats reference after risk checks`() {
        val request = baseRequest(
            referenceOdds = BigDecimal("1.90"),
            targetOdds = BigDecimal("0.95"),
            stakeAmount = BigDecimal("50.00")
        )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", activeStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals("prematch", decision.bettingMode)
        assertEquals("prematch", decision.matchPhase)
        assertEquals(BigDecimal("1.95000000"), decision.targetDecimalOdds)
        assertEquals(BigDecimal("0.05000000"), decision.decimalEdge)
        assertEquals("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", decision.dedupeKey)
        assertEquals("ready", captor.value.status)
        assertEquals(decision.dedupeKey, captor.value.activeDedupeKey)
    }

    @Test
    fun `crown alert rise signal creates ready intent without pinnacle reference`() {
        val request = baseRequest(
            bettingMode = "live",
            matchPhase = "live",
            leagueName = "瑞典杯",
            matchTitle = "米亚尔比 vs 哈马比",
            selectionName = "米亚尔比",
            referenceSourceKey = "crown",
            targetSourceKey = "crown",
            referenceOdds = BigDecimal("0.73"),
            targetOdds = BigDecimal("0.76"),
            stakeAmount = BigDecimal("50.00")
        )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:live:瑞典杯米亚尔比vs哈马比:handicap:-0.5:米亚尔比", activeStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals("crown", decision.referenceSourceKey)
        assertEquals("crown", decision.targetSourceKey)
        assertEquals(BigDecimal("1.76000000"), decision.targetDecimalOdds)
        assertEquals(BigDecimal("0.03000000"), decision.decimalEdge)
        assertEquals("ready", captor.value.status)
    }

    @Test
    fun `stale odds monitor signal is rejected before an intent can be executed`() {
        val request = baseRequest(capturedAt = 1000)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 40_000)

        assertEquals("rejected", decision.status)
        assertEquals("stale_signal", decision.reason)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        verify(repository).save(captor.capture())
        assertEquals(null, captor.value.activeDedupeKey)
    }

    @Test
    fun `unsupported market type is rejected`() {
        val request = baseRequest(marketType = "moneyline")
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("unsupported_market", decision.reason)
    }

    @Test
    fun `oversized stake is rejected`() {
        val request = baseRequest(stakeAmount = BigDecimal("1000.00"))
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("stake_over_single_limit", decision.reason)
    }

    @Test
    fun `weak crown edge is rejected`() {
        val request = baseRequest(referenceOdds = BigDecimal("1.94"), targetOdds = BigDecimal("0.95"))
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("edge_below_minimum", decision.reason)
        assertEquals(BigDecimal("0.01000000"), decision.decimalEdge)
    }

    @Test
    fun `crown signal below requested minimum target odds is rejected`() {
        val request = baseRequest(
            referenceSourceKey = "crown",
            targetSourceKey = "crown",
            referenceOdds = BigDecimal("0.80"),
            targetOdds = BigDecimal("0.68"),
            minimumTargetOdds = BigDecimal("0.70"),
            oddsChangeDirection = "drop"
        )
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("target_odds_below_minimum", decision.reason)
    }

    @Test
    fun `duplicate active intent for the same account match market and selection is rejected`() {
        val request = baseRequest()
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", activeStatuses()))
            .thenReturn(true)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("duplicate_active_intent", decision.reason)
    }

    @Test
    fun `account key is part of duplicate protection`() {
        val firstAccount = baseRequest(accountKey = "crown-a")
        val secondAccount = baseRequest(accountKey = "crown-b")
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val first = service.createIntent(firstAccount, now = 1_000_000)
        val second = service.createIntent(secondAccount, now = 1_000_000)

        assertTrue(first.dedupeKey.startsWith("crown-a:prematch:"))
        assertTrue(second.dedupeKey.startsWith("crown-b:prematch:"))
    }

    @Test
    fun `chinese match names keep distinct duplicate keys`() {
        val firstMatch = baseRequest(
            leagueName = "英超",
            matchTitle = "曼城 vs 阿森纳",
            selectionName = "曼城"
        )
        val secondMatch = baseRequest(
            leagueName = "西甲",
            matchTitle = "皇马 vs 巴塞罗那",
            selectionName = "皇马"
        )
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val first = service.createIntent(firstMatch, now = 1_000_000)
        val second = service.createIntent(secondMatch, now = 1_000_000)

        assertTrue(first.dedupeKey.contains("英超曼城vs阿森纳"))
        assertTrue(second.dedupeKey.contains("西甲皇马vs巴塞罗那"))
        assertTrue(first.dedupeKey != second.dedupeKey)
    }

    @Test
    fun `blank match and selection are rejected`() {
        val request = baseRequest(matchTitle = " ", selectionName = " ")
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("invalid_signal_content", decision.reason)
    }

    @Test
    fun `future odds monitor signal is rejected`() {
        val request = baseRequest(capturedAt = 1_060_001)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("future_signal", decision.reason)
    }

    @Test
    fun `signal is rejected when selected betting mode does not match captured phase`() {
        val request = baseRequest(bettingMode = "prematch", matchPhase = "live")
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("phase_mismatch", decision.reason)
    }

    @Test
    fun `unsupported betting mode is rejected`() {
        val request = baseRequest(bettingMode = "all", matchPhase = "prematch")
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("unsupported_betting_mode", decision.reason)
    }

    @Test
    fun `decision response includes source and league details`() {
        val request = baseRequest(leagueName = "英超")
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("odds_monitor", decision.signalSource)
        assertEquals("prematch", decision.bettingMode)
        assertEquals("prematch", decision.matchPhase)
        assertEquals("英超", decision.leagueName)
    }

    @Test
    fun `recent intents are returned with readable reasons`() {
        `when`(repository.findTop100ByOrderByCreatedAtDesc()).thenReturn(
            listOf(
                AutoBettingIntent(
                    dedupeKey = "default:live:英超曼城vs阿森纳:total:2.5:大球",
                    signalSource = "odds_monitor",
                    bettingMode = "live",
                    matchPhase = "live",
                    accountKey = "default",
                    leagueName = "英超",
                    matchTitle = "曼城 vs 阿森纳",
                    marketType = "total",
                    lineValue = "2.5",
                    selectionName = "大球",
                    referenceSourceKey = "pinnacle",
                    targetSourceKey = "crown",
                    referenceOdds = BigDecimal("1.92000000"),
                    targetOdds = BigDecimal("0.95000000"),
                    targetDecimalOdds = BigDecimal("1.95000000"),
                    decimalEdge = BigDecimal("0.03000000"),
                    stakeAmount = BigDecimal("50.0000"),
                    status = "ready",
                    capturedAt = 990_000,
                    createdAt = 1_000_000,
                    updatedAt = 1_000_000,
                )
            )
        )

        val result = service.listRecentIntents()

        assertEquals(1, result.size)
        assertEquals("accepted", result.first().reason)
        assertEquals("live", result.first().bettingMode)
        assertEquals("live", result.first().matchPhase)
        assertEquals("英超", result.first().leagueName)
    }

    @Test
    fun `successful history only returns placed intents verified in crown history`() {
        `when`(repository.findTop100ByStatusAndCrownHistoryVerifiedTrueOrderByCreatedAtDesc("placed")).thenReturn(
            listOf(
                AutoBettingIntent(
                    dedupeKey = "crown-a:live:英超曼城vs阿森纳:total:2.5:大球",
                    signalSource = "odds_monitor",
                    bettingMode = "live",
                    matchPhase = "live",
                    accountKey = "crown-a",
                    leagueName = "英超",
                    matchTitle = "曼城 vs 阿森纳",
                    marketType = "total",
                    lineValue = "2.5",
                    selectionName = "大球",
                    referenceSourceKey = "pinnacle",
                    targetSourceKey = "crown",
                    referenceOdds = BigDecimal("1.92000000"),
                    targetOdds = BigDecimal("0.95000000"),
                    targetDecimalOdds = BigDecimal("1.95000000"),
                    decimalEdge = BigDecimal("0.03000000"),
                    stakeAmount = BigDecimal("50.0000"),
                    status = "placed",
                    crownHistoryVerified = true,
                    crownHistoryCheckedAt = 1_000_500,
                    crownBetReference = "CROWN-10001",
                    capturedAt = 990_000,
                    createdAt = 1_000_000,
                    updatedAt = 1_000_500,
                )
            )
        )

        val result = service.listRecentVerifiedPlacedIntents()

        assertEquals(1, result.size)
        assertEquals("placed", result.first().status)
        assertEquals("crown_history_verified", result.first().reason)
        assertEquals(true, result.first().crownHistoryVerified)
        assertEquals(1_000_500, result.first().crownHistoryCheckedAt)
        assertEquals("CROWN-10001", result.first().crownBetReference)
    }

    private fun baseRequest(
        accountKey: String? = null,
        bettingMode: String = "prematch",
        matchPhase: String = "prematch",
        leagueName: String = "Premier League",
        matchTitle: String = "Arsenal v Chelsea",
        marketType: String = "handicap",
        selectionName: String = "home",
        referenceSourceKey: String = "pinnacle",
        targetSourceKey: String = "crown",
        referenceOdds: BigDecimal = BigDecimal("1.90"),
        targetOdds: BigDecimal = BigDecimal("0.95"),
        minimumTargetOdds: BigDecimal? = null,
        oddsChangeDirection: String? = null,
        stakeAmount: BigDecimal = BigDecimal("50.00"),
        capturedAt: Long = 990_000
    ) = AutoBettingSignalRequest(
        signalSource = "odds_monitor",
        accountKey = accountKey,
        bettingMode = bettingMode,
        matchPhase = matchPhase,
        leagueName = leagueName,
        matchTitle = matchTitle,
        marketType = marketType,
        lineValue = "-0.5",
        selectionName = selectionName,
        referenceSourceKey = referenceSourceKey,
        targetSourceKey = targetSourceKey,
        referenceOdds = referenceOdds,
        targetOdds = targetOdds,
        minimumTargetOdds = minimumTargetOdds,
        oddsChangeDirection = oddsChangeDirection,
        stakeAmount = stakeAmount,
        capturedAt = capturedAt
    )

    private fun activeStatuses() = listOf("ready", "placing", "placed_unverified", "placed")
}
