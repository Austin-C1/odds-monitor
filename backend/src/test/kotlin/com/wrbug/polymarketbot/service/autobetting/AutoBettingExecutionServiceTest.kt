package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.lang.reflect.Modifier
import java.util.Optional

class AutoBettingExecutionServiceTest {
    private val intentRepository = mock(AutoBettingIntentRepository::class.java)
    private val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
    private val gateway = mock(CrownBetPlacementGateway::class.java)
    private val bettingNotificationService = mock(AutoBettingNotificationService::class.java)
    private val service = AutoBettingExecutionService(
        intentRepository = intentRepository,
        platformMatchRepository = platformMatchRepository,
        objectMapper = jacksonObjectMapper(),
        crownBetPlacementGateway = gateway,
        bettingNotificationService = bettingNotificationService
    )

    @BeforeEach
    fun setUp() {
        `when`(
            intentRepository.markReadyIntentPlacingById(
                21L,
                "ready",
                "placing",
                2_000_000
            )
        ).thenReturn(1)
    }

    @Test
    fun `crown execution does not block all accounts behind one synchronized request`() {
        val method = AutoBettingExecutionService::class.java.methods.first {
            it.name == "executeCrownIntent" && it.parameterTypes.size == 3
        }

        assertEquals(false, Modifier.isSynchronized(method.modifiers))
    }

    @Test
    fun `ready crown intent keeps verified receipt reason after placement`() {
        val intent = liveHandicapIntent()
        val match = crownPlatformMatch()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                "沙特超级联赛",
                "阿尔菲斯",
                "纳加马安萘哉"
            )
        ).thenReturn(match)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(match),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-10001",
                message = "crown_receipt_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("crown_receipt_verified", result.reason)
        assertEquals(true, result.crownHistoryVerified)
        assertEquals(2_000_000, result.crownHistoryCheckedAt)
        assertEquals("CROWN-10001", result.crownBetReference)
        assertEquals(listOf("placing", "placed"), captor.allValues.map { it.status })
        assertEquals("CROWN-10001", captor.allValues.last().crownBetReference)
        verify(bettingNotificationService).sendPlacedIntent(captor.allValues.last(), 2_000_000)
    }

    @Test
    fun `verified crown receipt details are written to betting history immediately`() {
        val intent = liveHandicapIntent()
        val match = crownPlatformMatch()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent, match)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(match),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "OU23993783274",
                message = "crown_receipt_verified",
                currentOdds = BigDecimal("1.09000000"),
                verifiedRecord = CrownOpenBetRecord(
                    ticketReference = "OU23993783274",
                    leagueName = "International Friendly",
                    matchTitle = "Germany v Finland",
                    marketType = "moneyline",
                    lineValue = null,
                    selectionName = "Germany",
                    odds = BigDecimal("1.09"),
                    stakeAmount = BigDecimal("50.00"),
                    estimatedWin = BigDecimal("4.50"),
                    placedAtText = null
                )
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        val saved = captor.allValues.last()
        assertEquals("placed", result.status)
        assertEquals("OU23993783274", saved.crownBetReference)
        assertEquals("International Friendly", saved.leagueName)
        assertEquals("Germany v Finland", saved.matchTitle)
        assertEquals("moneyline", saved.marketType)
        assertEquals("Germany", saved.selectionName)
        assertEquals(BigDecimal("1.09000000"), saved.targetOdds)
        assertEquals(BigDecimal("50.0000"), saved.stakeAmount)
        assertEquals(true, saved.crownHistoryVerified)
    }

    @Test
    fun `disabled backend auto betting rejects ready intent before crown placement`() {
        val systemConfigService = mock(com.wrbug.polymarketbot.service.system.SystemConfigService::class.java)
        val guardedService = AutoBettingExecutionService(
            intentRepository = intentRepository,
            platformMatchRepository = platformMatchRepository,
            objectMapper = jacksonObjectMapper(),
            crownBetPlacementGateway = gateway,
            systemConfigService = systemConfigService,
            bettingNotificationService = bettingNotificationService
        )
        val intent = liveHandicapIntent()
        `when`(systemConfigService.isAutoBettingEnabled()).thenReturn(false)
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = guardedService.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("auto_betting_disabled", result.reason)
        assertEquals("rejected", captor.value.status)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `intent is rejected when crown current odds are below minimum target odds before placement`() {
        val intent = liveHandicapIntent()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                "沙特超级联赛",
                "阿尔菲斯",
                "纳加马安萘哉"
            )
        ).thenReturn(crownPlatformMatch())
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = false,
                historyVerified = false,
                ticketReference = null,
                message = "target_odds_below_minimum",
                currentOdds = BigDecimal("0.84000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("target_odds_below_minimum", result.reason)
        assertEquals(false, result.crownHistoryVerified)
        assertEquals(null, result.crownBetReference)
        assertEquals(listOf("placing", "rejected"), captor.allValues.map { it.status })
        assertEquals(null, captor.allValues.last().activeDedupeKey)
    }

    @Test
    fun `execution uses requested minimum target odds instead of original signal odds`() {
        val intent = liveHandicapIntent()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.80000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-10004",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.82000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/",
                minimumTargetOdds = BigDecimal("0.80")
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("CROWN-10004", result.crownBetReference)
    }

    @Test
    fun `prematch crown execution passes prematch phase to placement gateway`() {
        val intent = liveHandicapIntent().copy(
            dedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安萘哉:handicap:0/0.5:阿尔菲斯",
            activeDedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安萘哉:handicap:0/0.5:阿尔菲斯",
            bettingMode = "prematch",
            matchPhase = "prematch"
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchPhase = "prematch",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_RH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-PREMATCH-1",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("prematch", result.matchPhase)
        assertEquals("CROWN-PREMATCH-1", result.crownBetReference)
    }

    @Test
    fun `prematch total crown execution uses prematch total bet element id`() {
        val intent = liveHandicapIntent().copy(
            dedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安萘哉:total:2.5:大球",
            activeDedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安萘哉:total:2.5:大球",
            bettingMode = "prematch",
            matchPhase = "prematch",
            marketType = "total",
            lineValue = "2.5",
            selectionName = "大球"
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchPhase = "prematch",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = "total",
                    selectionName = "大球",
                    betElementId = "bet_8764315_11049615_OUC",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "2.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-PREMATCH-TOTAL-1",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("prematch", result.matchPhase)
        assertEquals("CROWN-PREMATCH-TOTAL-1", result.crownBetReference)
    }

    @Test
    fun `prematch crown execution chooses prematch crown match when live match with same teams is newer`() {
        val intent = liveHandicapIntent().copy(
            dedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安萘哉:handicap:0/0.5:阿尔菲斯",
            activeDedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安萘哉:handicap:0/0.5:阿尔菲斯",
            bettingMode = "prematch",
            matchPhase = "prematch"
        )
        val liveMatch = crownPlatformMatch(
            id = 901L,
            sourceMatchId = "live-8764315",
            gid = "live8764315",
            ecid = "live11049615",
            isLive = true
        )
        val prematchMatch = crownPlatformMatch(
            id = 902L,
            sourceMatchId = "prematch-8764315",
            gid = "prematch8764315",
            ecid = "prematch11049615",
            isLive = false
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                intent.leagueName,
                "阿尔菲斯",
                "纳加马安萘哉"
            )
        ).thenReturn(liveMatch)
        `when`(platformMatchRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(liveMatch, prematchMatch)
        )
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchPhase = "prematch",
                    matchTitle = crownMatchTitle(prematchMatch),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_prematch8764315_prematch11049615_RH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-PREMATCH-MATCH-1",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("prematch", result.matchPhase)
        assertEquals("CROWN-PREMATCH-MATCH-1", result.crownBetReference)
    }

    @Test
    fun `prematch crown execution rejects known live crown match when no prematch match exists`() {
        val intent = liveHandicapIntent().copy(
            dedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安营哈:handicap:0/0.5:阿尔菲斯",
            activeDedupeKey = "crown-seed-cuu07crbyfa:prematch:沙特超级联赛阿尔菲斯vs纳加马安营哈:handicap:0/0.5:阿尔菲斯",
            bettingMode = "prematch",
            matchPhase = "prematch"
        )
        val liveMatch = crownPlatformMatch(
            id = 901L,
            sourceMatchId = "live-8764315",
            gid = "live8764315",
            ecid = "live11049615",
            isLive = true
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                intent.leagueName,
                "闃垮皵鑿叉柉",
                "绾冲姞椹畨钀樺搲"
            )
        ).thenReturn(liveMatch)
        `when`(platformMatchRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(listOf(liveMatch))
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchPhase = "prematch",
                    matchTitle = crownMatchTitle(liveMatch),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_live8764315_live11049615_RH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-WRONG-LIVE-FALLBACK",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("crown_phase_mismatch", result.reason)
        assertEquals(listOf("placing", "rejected"), captor.allValues.map { it.status })
        verifyNoInteractions(gateway)
    }

    @Test
    fun `crown execution rejects crown match when page phase cannot be verified`() {
        val intent = liveHandicapIntent()
        val phaseUnknownMatch = crownPlatformMatch(isLive = null)
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent, phaseUnknownMatch)
        `when`(platformMatchRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(listOf(phaseUnknownMatch))
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(phaseUnknownMatch),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-PHASE-UNKNOWN",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("crown_phase_unknown", result.reason)
        assertEquals(listOf("placing", "rejected"), captor.allValues.map { it.status })
        verifyNoInteractions(gateway)
    }

    @Test
    fun `placed intent without crown history verification is not left in placing state`() {
        val intent = liveHandicapIntent()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = false,
                ticketReference = "CROWN-10002",
                message = "crown_history_unverified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed_unverified", result.status)
        assertEquals("crown_history_unverified", result.reason)
        assertEquals(false, result.crownHistoryVerified)
        assertEquals(2_000_000, result.crownHistoryCheckedAt)
        assertEquals("CROWN-10002", result.crownBetReference)
        assertEquals(listOf("placing", "placed_unverified"), captor.allValues.map { it.status })
        assertEquals("crown_history_unverified", captor.allValues.last().rejectReason)
    }

    @Test
    fun `failed crown placement does not put account into cooldown`() {
        val firstIntent = liveHandicapIntent()
        val secondIntent = liveHandicapIntent().copy(
            id = 22L,
            dedupeKey = liveHandicapIntent().dedupeKey + ":retry",
            activeDedupeKey = liveHandicapIntent().activeDedupeKey + ":retry"
        )
        val failure = CrownBetPlacementResult(
            placed = false,
            historyVerified = false,
            ticketReference = null,
            message = "crown_place_button_native_click_required",
            currentOdds = BigDecimal("0.87000000")
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(firstIntent))
        `when`(intentRepository.findById(22L)).thenReturn(Optional.of(secondIntent))
        `when`(
            intentRepository.markReadyIntentPlacingById(
                22L,
                "ready",
                "placing",
                2_001_000
            )
        ).thenReturn(1)
        stubCrownPlatformMatchFor(firstIntent)
        `when`(gateway.placeBet(expectedLiveHandicapCommand())).thenReturn(failure, failure)
        `when`(intentRepository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val first = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )
        val second = service.executeCrownIntent(
            intentId = 22L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_001_000
        )

        assertEquals("crown_place_button_native_click_required", first.reason)
        assertEquals("crown_place_button_native_click_required", second.reason)
        verify(gateway, times(2)).placeBet(expectedLiveHandicapCommand())
    }

    @Test
    fun `verified crown placement puts account into cooldown for following ready intent`() {
        val firstIntent = liveHandicapIntent()
        val secondIntent = liveHandicapIntent().copy(
            id = 22L,
            dedupeKey = liveHandicapIntent().dedupeKey + ":next",
            activeDedupeKey = liveHandicapIntent().activeDedupeKey + ":next"
        )
        val success = CrownBetPlacementResult(
            placed = true,
            historyVerified = true,
            ticketReference = "CROWN-COOLDOWN-1",
            message = "crown_history_verified",
            currentOdds = BigDecimal("0.87000000")
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(firstIntent))
        `when`(intentRepository.findById(22L)).thenReturn(Optional.of(secondIntent))
        `when`(
            intentRepository.markReadyIntentPlacingById(
                22L,
                "ready",
                "placing",
                2_001_000
            )
        ).thenReturn(1)
        stubCrownPlatformMatchFor(firstIntent)
        `when`(gateway.placeBet(expectedLiveHandicapCommand())).thenReturn(success, success)
        `when`(intentRepository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        val first = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )
        val second = service.executeCrownIntent(
            intentId = 22L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_001_000
        )

        assertEquals("placed", first.status)
        assertEquals("account_in_cooldown", second.reason)
        verify(gateway, times(1)).placeBet(expectedLiveHandicapCommand())
    }

    @Test
    fun `recent unverified crown placement waits before delayed history recheck`() {
        val intent = liveHandicapIntent().copy(
            status = "placed_unverified",
            rejectReason = "crown_history_unverified",
            crownHistoryVerified = false,
            crownHistoryCheckedAt = 1_990_000,
            crownBetReference = "CROWN-10002"
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed_unverified", result.status)
        assertEquals("crown_history_recheck_pending", result.reason)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `old unverified crown placement is rechecked and promoted when history appears`() {
        val intent = liveHandicapIntent().copy(
            status = "placed_unverified",
            rejectReason = "crown_history_unverified",
            crownHistoryVerified = false,
            crownHistoryCheckedAt = 1_900_000,
            crownBetReference = "CROWN-10002"
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.verifyPlacedBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                ),
                "CROWN-10002"
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-10002",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("crown_history_verified", result.reason)
        assertEquals(true, result.crownHistoryVerified)
        assertEquals("CROWN-10002", result.crownBetReference)
        assertEquals(listOf("placing", "placed"), captor.allValues.map { it.status })
    }

    @Test
    fun `gateway exception rejects intent instead of leaving it in placing state`() {
        val intent = liveHandicapIntent()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = crownMatchTitle(crownPlatformMatch()),
                    marketType = intent.marketType,
                    selectionName = intent.selectionName,
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenThrow(RuntimeException("cdp closed"))
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("crown_execution_error", result.reason)
        assertEquals(listOf("placing", "rejected"), captor.allValues.map { it.status })
        assertEquals(null, captor.allValues.last().activeDedupeKey)
    }

    @Test
    fun `execution resolves crown match when telegram match name uses another platform alias`() {
        val intent = liveHandicapIntent().copy(
            matchTitle = "Tokyo vs Kawasaki Frontale",
            leagueName = "Japan J1",
            selectionName = "Tokyo"
        )
        val crownMatch = crownPlatformMatch().copy(
            rawLeagueName = "Japan - J League",
            rawHomeTeam = "FC Tokyo",
            rawAwayTeam = "Kawasaki Frontale"
        )
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                "Japan J1",
                "Tokyo",
                "Kawasaki Frontale"
            )
        ).thenReturn(null)
        `when`(platformMatchRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(listOf(crownMatch))
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    matchTitle = "FC Tokyo vs Kawasaki Frontale",
                    marketType = "handicap",
                    selectionName = "Tokyo",
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-10003",
                message = "crown_history_verified",
                currentOdds = BigDecimal("0.87000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/"
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("CROWN-10003", result.crownBetReference)
    }

    private fun stubCrownPlatformMatchFor(
        intent: AutoBettingIntent,
        match: OddsPlatformMatch = crownPlatformMatch(isLive = intent.matchPhase == "live")
    ) {
        val teams = intent.matchTitle.split(Regex("""\s+vs\s+|\s+v\s+""", RegexOption.IGNORE_CASE), limit = 2)
            .map { it.trim() }
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                intent.leagueName,
                teams[0],
                teams[1]
            )
        ).thenReturn(match)
    }

    private fun liveHandicapIntent() = AutoBettingIntent(
        id = 21L,
        dedupeKey = "crown-seed-cuu07crbyfa:live:沙特超级联赛阿尔菲斯vs纳加马安萘哉:handicap:0/0.5:阿尔菲斯",
        activeDedupeKey = "crown-seed-cuu07crbyfa:live:沙特超级联赛阿尔菲斯vs纳加马安萘哉:handicap:0/0.5:阿尔菲斯",
        signalSource = "odds_monitor",
        bettingMode = "live",
        matchPhase = "live",
        accountKey = "crown-seed-cuu07crbyfa",
        accountDisplayName = "皇冠主号",
        leagueName = "沙特超级联赛",
        matchTitle = "阿尔菲斯 vs 纳加马安萘哉",
        marketType = "handicap",
        lineValue = "0/0.5",
        selectionName = "阿尔菲斯",
        referenceSourceKey = "crown",
        targetSourceKey = "crown",
        referenceOdds = BigDecimal("0.79000000"),
        targetOdds = BigDecimal("0.87000000"),
        targetDecimalOdds = BigDecimal("1.87000000"),
        decimalEdge = BigDecimal("0.08000000"),
        stakeAmount = BigDecimal("10.0000"),
        status = "ready",
        capturedAt = 1_990_000,
        createdAt = 1_995_000,
        updatedAt = 1_995_000
    )

    private fun crownPlatformMatch(
        id: Long = 837L,
        sourceMatchId: String = "8764315",
        gid: String = "8764315",
        ecid: String = "11049615",
        isLive: Boolean? = true
    ) = OddsPlatformMatch(
        id = id,
        sourceKey = "crown",
        sourceMatchId = sourceMatchId,
        rawLeagueName = "沙特超级联赛",
        rawHomeTeam = "阿尔菲斯",
        rawAwayTeam = "纳加马安萘哉",
        rawPayloadJson = buildCrownRawPayload(gid, ecid, isLive),
        createdAt = 1_900_000,
        updatedAt = 1_999_000
    )

    private fun buildCrownRawPayload(gid: String, ecid: String, isLive: Boolean?): String {
        val liveField = isLive?.let { ""","is_live": $it""" }.orEmpty()
        return """
            {
              "gid": "$gid",
              "ecid": "$ecid",
              "team_h": "阿尔菲斯",
              "team_c": "纳加马安萘哉"$liveField
            }
        """.trimIndent()
    }

    private fun crownMatchTitle(match: OddsPlatformMatch) = "${match.rawHomeTeam} vs ${match.rawAwayTeam}"

    private fun expectedLiveHandicapCommand() = CrownBetPlacementCommand(
        profileId = "k1chipm1",
        loginUrl = "https://m407.mos077.com/",
        matchTitle = crownMatchTitle(crownPlatformMatch()),
        marketType = liveHandicapIntent().marketType,
        selectionName = liveHandicapIntent().selectionName,
        betElementId = "bet_8764315_11049615_REH",
        stakeAmount = BigDecimal("10.0000"),
        targetOdds = BigDecimal("0.87000000"),
        lineValue = "0/0.5"
    )
}
