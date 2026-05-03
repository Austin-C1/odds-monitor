package com.wrbug.polymarketbot.service.accounts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PositionCheckServiceImplementationTest {

    private val sourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "accounts",
        "PositionCheckService.kt"
    )

    @Test
    fun `position settlement should not save tracking rows directly inside suspend workflow`() {
        val source = Files.readString(sourcePath)

        assertFalse(
            source.contains("copyOrderTrackingRepository.save(order)"),
            "PositionCheckService should delegate settlement writes to a transactional writer"
        )
    }
}
