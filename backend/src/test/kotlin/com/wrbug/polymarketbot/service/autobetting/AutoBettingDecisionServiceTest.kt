package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.dto.AutoBettingSignalRequest
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.service.system.SystemConfigService
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
            referenceOdds = BigDecimal("0.90"),
            targetOdds = BigDecimal("0.95"),
            stakeAmount = BigDecimal("50.00")
        )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
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
        assertEquals(null, captor.value.activeDedupeKey)
    }

    @Test
    fun `crown alert rise signal creates ready intent without external reference`() {
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
        `when`(repository.existsByDedupeKeyAndStatusIn("default:live:瑞典杯米亚尔比vs哈马比:handicap:-0.5:米亚尔比", lockedStatuses()))
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
    fun `monitor signal decision echoes frontend queue metadata`() {
        val request = baseRequest(
            referenceSourceKey = "crown",
            targetSourceKey = "crown",
            referenceOdds = BigDecimal("0.73"),
            targetOdds = BigDecimal("0.76"),
            queuePosition = 2,
            queueTotal = 6
        )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals(2, decision.queuePosition)
        assertEquals(6, decision.queueTotal)
    }

    @Test
    fun `disabled backend auto betting records rejected signal intent`() {
        val systemConfigService = mock(SystemConfigService::class.java)
        val guardedService = AutoBettingDecisionService(repository, systemConfigService)
        `when`(systemConfigService.isAutoBettingEnabled()).thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = guardedService.createIntent(
            baseRequest(accountKey = "crown-account-1", accountDisplayName = "皇冠一号"),
            now = 1_000_000
        )

        assertEquals("rejected", decision.status)
        assertEquals("auto_betting_disabled", decision.reason)
        assertEquals("皇冠一号", decision.accountDisplayName)
        assertEquals("rejected", captor.value.status)
        assertEquals("auto_betting_disabled", captor.value.rejectReason)
    }

    @Test
    fun `crown alert drop signal creates ready intent for reverse betting`() {
        val request = baseRequest(
            bettingMode = "live",
            matchPhase = "live",
            leagueName = "英超",
            matchTitle = "曼城 vs 利物浦",
            selectionName = "利物浦",
            referenceSourceKey = "crown",
            targetSourceKey = "crown",
            referenceOdds = BigDecimal("1.10"),
            targetOdds = BigDecimal("1.06"),
            oddsChangeDirection = "drop",
            stakeAmount = BigDecimal("50.00")
        )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:live:英超曼城vs利物浦:handicap:-0.5:利物浦", lockedStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(BigDecimal("0.04000000"), decision.decimalEdge)
        assertEquals("ready", captor.value.status)
        assertEquals(null, captor.value.activeDedupeKey)
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
    fun `signal age limit can be extended by the betting settings`() {
        val request = baseRequest(capturedAt = 1_000, maxSignalAgeSeconds = 120)
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 80_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals("ready", captor.value.status)
    }

    @Test
    fun `custom signal age limit still rejects older signals`() {
        val request = baseRequest(capturedAt = 1_000, maxSignalAgeSeconds = 120)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 122_001)

        assertEquals("rejected", decision.status)
        assertEquals("stale_signal", decision.reason)
    }

    @Test
    fun `stale incoming signal still releases stale ready intent for the same signal`() {
        val request = baseRequest(capturedAt = 1_000, maxSignalAgeSeconds = 120)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 122_001)

        verify(repository).rejectStaleReadyIntentByDedupeKey(
            dedupeKey = "default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home",
            readyStatus = "ready",
            capturedBefore = 2_001,
            rejectedStatus = "rejected",
            rejectReason = "stale_signal",
            updatedAt = 122_001
        )
        assertEquals("rejected", decision.status)
        assertEquals("stale_signal", decision.reason)
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
    fun `account total stake limit rejects a signal that would exceed the account cap`() {
        val request = baseRequest(
            accountKey = "crown-a",
            stakeAmount = BigDecimal("50.00"),
            accountStakeLimit = BigDecimal("100.00")
        )
        `when`(repository.existsByDedupeKeyAndStatusIn("crown-a:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        `when`(repository.sumStakeAmountByAccountKeyAndStatusIn("crown-a", lockedStatuses()))
            .thenReturn(BigDecimal("80.0000"))
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("account_stake_limit_reached", decision.reason)
    }

    @Test
    fun `weak crown edge is accepted when target water is above the configured floor`() {
        val request = baseRequest(referenceOdds = BigDecimal("0.94"), targetOdds = BigDecimal("0.95"))
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(BigDecimal("0.01000000"), decision.decimalEdge)
        assertEquals(null, captor.value.activeDedupeKey)
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
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(true)
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("duplicate_active_intent", decision.reason)
    }

    @Test
    fun `already placed intent for same account signal returns placed duplicate reason`() {
        val request = baseRequest()
        `when`(
            repository.findTopByDedupeKeyAndStatusInOrderByCreatedAtDesc(
                "default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home",
                lockedStatuses()
            )
        ).thenReturn(
            AutoBettingIntent(
                dedupeKey = "default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home",
                signalSource = "odds_monitor",
                bettingMode = "prematch",
                matchPhase = "prematch",
                accountKey = "default",
                leagueName = "Premier League",
                matchTitle = "Arsenal v Chelsea",
                marketType = "handicap",
                lineValue = "-0.5",
                selectionName = "home",
                referenceSourceKey = "crown",
                targetSourceKey = "crown",
                referenceOdds = BigDecimal("1.90000000"),
                targetOdds = BigDecimal("0.95000000"),
                targetDecimalOdds = BigDecimal("1.95000000"),
                decimalEdge = BigDecimal("0.05000000"),
                stakeAmount = BigDecimal("50.0000"),
                status = "placed",
                capturedAt = 990_000,
                createdAt = 990_000,
                updatedAt = 990_000
            )
        )
        `when`(repository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("rejected", decision.status)
        assertEquals("duplicate_placed_intent", decision.reason)
    }

    @Test
    fun `old crown page touched failure does not block duplicate retry after cooldown`() {
        val request = baseRequest()
        `when`(repository.findTopByDedupeKeyOrderByCreatedAtDesc("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home"))
            .thenReturn(
                AutoBettingIntent(
                    dedupeKey = "default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home",
                    signalSource = "odds_monitor",
                    bettingMode = "prematch",
                    matchPhase = "prematch",
                    accountKey = "default",
                    leagueName = "Premier League",
                    matchTitle = "Arsenal v Chelsea",
                    marketType = "handicap",
                    lineValue = "-0.5",
                    selectionName = "home",
                    referenceSourceKey = "crown",
                    targetSourceKey = "crown",
                    referenceOdds = BigDecimal("1.90000000"),
                    targetOdds = BigDecimal("0.95000000"),
                    targetDecimalOdds = BigDecimal("1.95000000"),
                    decimalEdge = BigDecimal("0.05000000"),
                    stakeAmount = BigDecimal("50.0000"),
                    status = "rejected",
                    rejectReason = "crown_page_not_found",
                    capturedAt = 690_000,
                    createdAt = 690_000,
                    updatedAt = 690_000
                )
            )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(null, captor.value.activeDedupeKey)
    }

    @Test
    fun `recent failed crown attempt does not block duplicate retry`() {
        val request = baseRequest()
        `when`(repository.findTopByDedupeKeyOrderByCreatedAtDesc("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home"))
            .thenReturn(
                AutoBettingIntent(
                    dedupeKey = "default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home",
                    signalSource = "odds_monitor",
                    bettingMode = "prematch",
                    matchPhase = "prematch",
                    accountKey = "default",
                    leagueName = "Premier League",
                    matchTitle = "Arsenal v Chelsea",
                    marketType = "handicap",
                    lineValue = "-0.5",
                    selectionName = "home",
                    referenceSourceKey = "crown",
                    targetSourceKey = "crown",
                    referenceOdds = BigDecimal("1.90000000"),
                    targetOdds = BigDecimal("0.95000000"),
                    targetDecimalOdds = BigDecimal("1.95000000"),
                    decimalEdge = BigDecimal("0.05000000"),
                    stakeAmount = BigDecimal("50.0000"),
                    status = "rejected",
                    rejectReason = "crown_page_not_found",
                    capturedAt = 990_000,
                    createdAt = 995_000,
                    updatedAt = 995_000
                )
            )
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(null, captor.value.activeDedupeKey)
    }

    @Test
    fun `qualifying signal is accepted when no active intent exists`() {
        val request = baseRequest()
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(null, captor.value.activeDedupeKey)
    }

    @Test
    fun `crown signal at requested minimum target odds is accepted without a minimum edge requirement`() {
        val request = baseRequest(
            referenceSourceKey = "crown",
            targetSourceKey = "crown",
            referenceOdds = BigDecimal("1.07"),
            targetOdds = BigDecimal("1.08"),
            minimumTargetOdds = BigDecimal("1.08")
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(BigDecimal("0.01000000"), decision.decimalEdge)
        assertEquals(null, captor.value.activeDedupeKey)
    }

    @Test
    fun `stale ready intent for the same signal is released before duplicate check`() {
        val request = baseRequest(capturedAt = 990_000, maxSignalAgeSeconds = 600)
        `when`(repository.existsByDedupeKeyAndStatusIn("default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home", lockedStatuses()))
            .thenReturn(false)
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val decision = service.createIntent(request, now = 1_000_000)

        verify(repository).rejectStaleReadyIntentByDedupeKey(
            dedupeKey = "default:prematch:premierleaguearsenalvchelsea:handicap:-0.5:home",
            readyStatus = "ready",
            capturedBefore = 400_000,
            rejectedStatus = "rejected",
            rejectReason = "stale_signal",
            updatedAt = 1_000_000
        )
        assertEquals("ready", decision.status)
        assertEquals("accepted", decision.reason)
        assertEquals(null, captor.value.activeDedupeKey)
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
                    referenceSourceKey = "crown",
                    targetSourceKey = "crown",
                    referenceOdds = BigDecimal("0.92000000"),
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
                    referenceSourceKey = "crown",
                    targetSourceKey = "crown",
                    referenceOdds = BigDecimal("0.92000000"),
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
        accountDisplayName: String? = null,
        bettingMode: String = "prematch",
        matchPhase: String = "prematch",
        leagueName: String = "Premier League",
        matchTitle: String = "Arsenal v Chelsea",
        marketType: String = "handicap",
        selectionName: String = "home",
        referenceSourceKey: String = "crown",
        targetSourceKey: String = "crown",
        referenceOdds: BigDecimal = BigDecimal("0.90"),
        targetOdds: BigDecimal = BigDecimal("0.95"),
        minimumTargetOdds: BigDecimal? = null,
        oddsChangeDirection: String? = null,
        stakeAmount: BigDecimal = BigDecimal("50.00"),
        accountStakeLimit: BigDecimal? = null,
        capturedAt: Long = 990_000,
        maxSignalAgeSeconds: Long? = null,
        queuePosition: Int? = null,
        queueTotal: Int? = null
    ) = AutoBettingSignalRequest(
        signalSource = "odds_monitor",
        accountKey = accountKey,
        accountDisplayName = accountDisplayName,
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
        accountStakeLimit = accountStakeLimit,
        capturedAt = capturedAt,
        maxSignalAgeSeconds = maxSignalAgeSeconds,
        queuePosition = queuePosition,
        queueTotal = queueTotal
    )

    private fun lockedStatuses() = listOf("ready", "placing", "placed", "placed_unverified")
}
