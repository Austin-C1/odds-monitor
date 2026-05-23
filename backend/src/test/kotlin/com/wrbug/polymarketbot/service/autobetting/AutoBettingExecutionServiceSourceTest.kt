package com.wrbug.polymarketbot.service.autobetting

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AutoBettingExecutionServiceSourceTest {
    @Test
    fun `crown placement gateway call is protected by profile execution lock`() {
        val source = File("src/main/kotlin/com/wrbug/polymarketbot/service/autobetting/AutoBettingExecutionService.kt")
            .readText()
        val lockBlock = source.substringAfter("profileExecutionLock.withProfileLock(profileId)", "")
            .substringBefore("if (placement.placed")

        assertTrue(lockBlock.contains("crownBetPlacementGateway.placeBet(command)"))
    }
}
