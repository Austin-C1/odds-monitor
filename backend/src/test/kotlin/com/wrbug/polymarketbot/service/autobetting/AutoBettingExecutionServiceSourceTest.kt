package com.wrbug.polymarketbot.service.autobetting

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AutoBettingExecutionServiceSourceTest {
    @Test
    fun `crown placement gateway call is protected by account execution lock`() {
        val source = File("src/main/kotlin/com/wrbug/polymarketbot/service/autobetting/AutoBettingExecutionService.kt")
            .readText()
        val lockBlock = source.substringAfter("profileExecutionLock.withAccountLock(placing.accountKey, profileId)", "")
            .substringBefore("if (placement.placed")

        assertTrue(lockBlock.contains("crownBetPlacementGateway.placeBet(command)"))
    }
}
