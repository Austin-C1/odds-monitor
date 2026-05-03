package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.controller.accounts.AccountController
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberFunctions

class AccountCoroutineRulesTest {

    @Test
    fun `account service request handlers should be suspend when they call coroutine work`() {
        val requiredSuspendMethods = setOf(
            "importAccount",
            "updateAccount",
            "getAccountDetail",
            "getAccountBalance"
        )

        val invalidMethods = AccountService::class.declaredMemberFunctions
            .filter { it.name in requiredSuspendMethods && !it.isSuspend }
            .map { it.name }

        assertTrue(
            invalidMethods.isEmpty(),
            "AccountService methods should be suspend: $invalidMethods"
        )
    }

    @Test
    fun `account controller handlers should be suspend instead of blocking on coroutine work`() {
        val requiredSuspendMethods = setOf(
            "checkProxyOptions",
            "importAccount",
            "updateAccount",
            "checkSetupStatus",
            "executeSetupStep",
            "getAccountDetail",
            "getAccountBalance",
            "getAllPositions",
            "sellPosition",
            "getRedeemablePositionsSummary",
            "redeemPositions"
        )

        val invalidMethods = AccountController::class.declaredMemberFunctions
            .filter { it.name in requiredSuspendMethods && !it.isSuspend }
            .map { it.name }

        assertTrue(
            invalidMethods.isEmpty(),
            "AccountController methods should be suspend: $invalidMethods"
        )
    }
}
