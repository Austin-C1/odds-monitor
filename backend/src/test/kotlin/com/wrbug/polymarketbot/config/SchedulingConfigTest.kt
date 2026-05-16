package com.wrbug.polymarketbot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchedulingConfigTest {
    @Test
    fun `scheduled tasks use a pool so one slow collector does not block every scheduled job`() {
        val scheduler = SchedulingConfig().taskScheduler()

        assertTrue(scheduler.scheduledThreadPoolExecutor.corePoolSize >= 4)
        assertEquals("scheduled-task-", scheduler.threadNamePrefix)

        scheduler.shutdown()
    }
}
