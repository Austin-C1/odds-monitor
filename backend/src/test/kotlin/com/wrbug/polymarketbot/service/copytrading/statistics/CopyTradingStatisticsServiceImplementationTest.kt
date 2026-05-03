package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CopyTradingStatisticsServiceImplementationTest {

    private val sourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "copytrading",
        "statistics",
        "CopyTradingStatisticsService.kt"
    )

    @Test
    fun `batch statistics should not loop through getStatistics one by one`() {
        val source = Files.readString(sourcePath)

        assertFalse(
            source.contains("for (copyTradingId in normalizedIds)") &&
                source.contains("getStatistics(copyTradingId)"),
            "getStatisticsBatch should load data in batches instead of calling getStatistics per id"
        )
    }

    @Test
    fun `matched order list should not refetch sell records one by one`() {
        val source = Files.readString(sourcePath)

        assertFalse(
            source.contains("matchRecordIds.mapNotNull { id ->") &&
                source.contains("sellMatchRecordRepository.findById(id)"),
            "getMatchedOrderList should load sell match records in batch"
        )

        assertFalse(
            source.contains("pagedMatchRecordIds.mapNotNull { id ->") &&
                source.contains("sellMatchRecordRepository.findById(id)"),
            "paged matched order records should also be loaded in batch"
        )
    }
}
