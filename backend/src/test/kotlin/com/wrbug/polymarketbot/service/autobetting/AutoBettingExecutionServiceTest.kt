package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.util.Optional

class AutoBettingExecutionServiceTest {
    private val intentRepository = mock(AutoBettingIntentRepository::class.java)
    private val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
    private val gateway = mock(CrownBetPlacementGateway::class.java)
    private val service = AutoBettingExecutionService(
        intentRepository = intentRepository,
        platformMatchRepository = platformMatchRepository,
        objectMapper = jacksonObjectMapper(),
        crownBetPlacementGateway = gateway
    )

    @Test
    fun `ready crown intent is marked placed only after crown history verification`() {
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
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    oddsTolerance = BigDecimal("0.02"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = true,
                historyVerified = true,
                ticketReference = "CROWN-10001",
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
                loginUrl = "https://m407.mos077.com/",
                oddsTolerance = BigDecimal("0.02")
            ),
            now = 2_000_000
        )

        assertEquals("placed", result.status)
        assertEquals("crown_history_verified", result.reason)
        assertEquals(true, result.crownHistoryVerified)
        assertEquals(2_000_000, result.crownHistoryCheckedAt)
        assertEquals("CROWN-10001", result.crownBetReference)
        assertEquals(listOf("placing", "placed"), captor.allValues.map { it.status })
        assertEquals("CROWN-10001", captor.allValues.last().crownBetReference)
    }

    @Test
    fun `intent is rejected when crown odds moved outside tolerance before placement`() {
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
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    oddsTolerance = BigDecimal("0.02"),
                    lineValue = "0/0.5"
                )
            )
        ).thenReturn(
            CrownBetPlacementResult(
                placed = false,
                historyVerified = false,
                ticketReference = null,
                message = "crown_odds_moved",
                currentOdds = BigDecimal("0.98000000")
            )
        )
        val captor = ArgumentCaptor.forClass(AutoBettingIntent::class.java)
        `when`(intentRepository.save(captor.capture())).thenAnswer { invocation -> invocation.arguments[0] }

        val result = service.executeCrownIntent(
            intentId = 21L,
            request = AutoBettingExecutionRequest(
                profileId = "k1chipm1",
                loginUrl = "https://m407.mos077.com/",
                oddsTolerance = BigDecimal("0.02")
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("crown_odds_moved", result.reason)
        assertEquals(false, result.crownHistoryVerified)
        assertEquals(null, result.crownBetReference)
        assertEquals(listOf("placing", "rejected"), captor.allValues.map { it.status })
        assertEquals(null, captor.allValues.last().activeDedupeKey)
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
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    oddsTolerance = BigDecimal("0.02"),
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
                loginUrl = "https://m407.mos077.com/",
                oddsTolerance = BigDecimal("0.02")
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
    fun `gateway exception rejects intent instead of leaving it in placing state`() {
        val intent = liveHandicapIntent()
        `when`(intentRepository.findById(21L)).thenReturn(Optional.of(intent))
        stubCrownPlatformMatchFor(intent)
        `when`(
            gateway.placeBet(
                CrownBetPlacementCommand(
                    profileId = "k1chipm1",
                    loginUrl = "https://m407.mos077.com/",
                    betElementId = "bet_8764315_11049615_REH",
                    stakeAmount = BigDecimal("10.0000"),
                    targetOdds = BigDecimal("0.87000000"),
                    oddsTolerance = BigDecimal("0.02"),
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
                loginUrl = "https://m407.mos077.com/",
                oddsTolerance = BigDecimal("0.02")
            ),
            now = 2_000_000
        )

        assertEquals("rejected", result.status)
        assertEquals("crown_execution_error", result.reason)
        assertEquals(listOf("placing", "rejected"), captor.allValues.map { it.status })
        assertEquals(null, captor.allValues.last().activeDedupeKey)
    }

    private fun stubCrownPlatformMatchFor(intent: AutoBettingIntent, match: OddsPlatformMatch = crownPlatformMatch()) {
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

    private fun crownPlatformMatch() = OddsPlatformMatch(
        id = 837L,
        sourceKey = "crown",
        sourceMatchId = "8764315",
        rawLeagueName = "沙特超级联赛",
        rawHomeTeam = "阿尔菲斯",
        rawAwayTeam = "纳加马安萘哉",
        rawPayloadJson = """
            {
              "gid": "8764315",
              "ecid": "11049615",
              "team_h": "阿尔菲斯",
              "team_c": "纳加马安萘哉"
            }
        """.trimIndent(),
        createdAt = 1_900_000,
        updatedAt = 1_999_000
    )
}
