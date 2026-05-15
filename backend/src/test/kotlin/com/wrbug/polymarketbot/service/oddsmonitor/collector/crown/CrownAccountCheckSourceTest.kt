package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CrownAccountCheckSourceTest {
    private val source = Files.readString(
        Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/crown/CrownApiClient.kt")
    )

    @Test
    fun `account check verifies login and account balance then exits session`() {
        val checkBlock = source.substringAfter("fun checkAccount").substringBefore("private fun accountCheckMessage")
        assertTrue(source.contains("performLogin(config)"))
        assertTrue(checkBlock.contains("fetchAccountBalance(session)"))
        assertFalse(checkBlock.contains("fetchMatchesWithSession"))
        assertTrue(source.contains("\"p\" to \"get_member_data\""))
        assertTrue(source.contains("\"change\" to \"all\""))
        assertTrue(source.contains("finally {"))
        assertTrue(source.contains("logout(session)"))
        assertTrue(source.contains("private fun logout(session: CrownSession)"))
        assertTrue(source.contains("runCatching"))
    }

    @Test
    fun `account check catches network exceptions as account errors`() {
        assertTrue(source.contains("catch (ex: Exception)"))
        assertTrue(source.contains("crown account check failed"))
    }
}
