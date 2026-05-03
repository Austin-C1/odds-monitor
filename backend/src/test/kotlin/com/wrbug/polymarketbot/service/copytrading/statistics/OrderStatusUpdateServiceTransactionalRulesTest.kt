package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import kotlin.reflect.full.declaredMemberFunctions

class OrderStatusUpdateServiceTransactionalRulesTest {

    @Test
    fun `transactional methods in order status updater are not suspend`() {
        val invalidMethods = OrderStatusUpdateService::class.declaredMemberFunctions
            .filter { function ->
                function.annotations.any { it is Transactional } && function.isSuspend
            }
            .map { it.name }

        assertTrue(
            invalidMethods.isEmpty(),
            "Transactional methods must be non-suspend: $invalidMethods"
        )
    }
}
