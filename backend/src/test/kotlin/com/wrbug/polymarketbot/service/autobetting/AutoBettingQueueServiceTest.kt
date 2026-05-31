package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.dto.AutoBettingCrownQueueAccountRequest
import com.wrbug.polymarketbot.dto.AutoBettingQueuedCrownExecutionRequest
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.anyCollection
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.util.Optional

class AutoBettingQueueServiceTest {
    private val intentRepository = mock(AutoBettingIntentRepository::class.java)
    private val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
    private val placedCommands = mutableListOf<CrownBetPlacementCommand>()
    private val gateway = object : CrownBetPlacementGateway {
        override fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult {
            placedCommands += command
            return if (command.profileId == "profile-a") {
                CrownBetPlacementResult(true, true, "CROWN-A", "crown_history_verified")
            } else {
                CrownBetPlacementResult(false, false, null, "crown_page_not_found")
            }
        }
    }
    private val decisionService = AutoBettingDecisionService(intentRepository)
    private val executionService = AutoBettingExecutionService(
        intentRepository = intentRepository,
        platformMatchRepository = platformMatchRepository,
        objectMapper = jacksonObjectMapper(),
        crownBetPlacementGateway = gateway
    )
    private val service = AutoBettingQueueService(decisionService, executionService)

    @Test
    fun `queued crown signal creates intents and executes enabled accounts in backend order`() {
        val saved = linkedMapOf<Long, AutoBettingIntent>()
        var nextId = 101L
        `when`(intentRepository.findTopByDedupeKeyOrderByCreatedAtDesc(anyString())).thenReturn(null)
        `when`(intentRepository.findTopByDedupeKeyAndStatusInOrderByCreatedAtDesc(anyString(), anyCollection()))
            .thenReturn(null)
        `when`(intentRepository.existsByDedupeKeyAndStatusIn(anyString(), anyCollection())).thenReturn(false)
        `when`(intentRepository.save(any(AutoBettingIntent::class.java))).thenAnswer { invocation ->
            val intent = invocation.arguments[0] as AutoBettingIntent
            val id = intent.id ?: nextId++
            intent.copy(id = id).also { saved[id] = it }
        }
        `when`(intentRepository.findById(anyLong())).thenAnswer { invocation ->
            Optional.ofNullable(saved[invocation.arguments[0] as Long])
        }
        `when`(
            intentRepository.markReadyIntentPlacingById(
                anyLong(),
                anyString(),
                anyString(),
                anyLong()
            )
        ).thenAnswer { invocation ->
            val id = invocation.arguments[0] as Long
            val current = saved[id] ?: return@thenAnswer 0
            if (current.status != "ready") return@thenAnswer 0
            saved[id] = current.copy(status = "placing", updatedAt = invocation.arguments[3] as Long)
            1
        }
        val crownMatch = OddsPlatformMatch(
            id = 501L,
            sourceKey = "crown",
            sourceMatchId = "8764315",
            rawLeagueName = "Premier League",
            rawHomeTeam = "Arsenal",
            rawAwayTeam = "Chelsea",
            rawPayloadJson = """{"gid":"8764315","ecid":"11049615","is_live":true}""",
            createdAt = 900_000,
            updatedAt = 999_000
        )
        `when`(
            platformMatchRepository.findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                "Premier League",
                "Arsenal",
                "Chelsea"
            )
        ).thenReturn(crownMatch)
        val result = service.executeQueuedCrownSignal(queueRequest(), now = 1_000_000)

        assertEquals(listOf("placed", "rejected"), result.map { it.status })
        assertEquals(listOf("account-a", "account-b"), result.map { it.accountKey })
        assertEquals(listOf("CROWN-A", null), result.map { it.crownBetReference })
        assertEquals(listOf("placed", "rejected"), saved.values.map { it.status })
        assertEquals(listOf(queueCommand("profile-a"), queueCommand("profile-b")), placedCommands)
    }

    private fun queueRequest() = AutoBettingQueuedCrownExecutionRequest(
        signalSource = "odds_monitor",
        bettingMode = "live",
        matchPhase = "live",
        leagueName = "Premier League",
        matchTitle = "Arsenal v Chelsea",
        marketType = "handicap",
        lineValue = "-0.5",
        selectionName = "Arsenal",
        referenceSourceKey = "crown",
        targetSourceKey = "crown",
        referenceOdds = BigDecimal("0.90"),
        targetOdds = BigDecimal("0.95"),
        minimumTargetOdds = BigDecimal("0.70"),
        oddsChangeDirection = "rise",
        stakeAmount = BigDecimal("50.00"),
        accountStakeLimit = BigDecimal("100.00"),
        capturedAt = 990_000,
        maxSignalAgeSeconds = 360,
        accounts = listOf(
            AutoBettingCrownQueueAccountRequest("account-a", "Account A", "profile-a", "https://m407.mos077.com/"),
            AutoBettingCrownQueueAccountRequest("account-b", "Account B", "profile-b", "https://m407.mos077.com/"),
            AutoBettingCrownQueueAccountRequest("account-c", "Account C", "profile-c", "https://m407.mos077.com/", false)
        )
    )

    private fun queueCommand(profileId: String) = CrownBetPlacementCommand(
        profileId = profileId,
        loginUrl = "https://m407.mos077.com/",
        matchPhase = "live",
        matchTitle = "Arsenal vs Chelsea",
        marketType = "handicap",
        selectionName = "arsenal",
        betElementId = "bet_8764315_11049615_REH",
        stakeAmount = BigDecimal("50.0000"),
        targetOdds = BigDecimal("0.95000000"),
        lineValue = "-0.5"
    )
}
