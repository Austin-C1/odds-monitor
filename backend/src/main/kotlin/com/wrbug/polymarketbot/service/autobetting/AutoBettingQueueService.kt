package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.dto.AutoBettingCrownQueueAccountRequest
import com.wrbug.polymarketbot.dto.AutoBettingDecisionDto
import com.wrbug.polymarketbot.dto.AutoBettingQueuedCrownExecutionRequest
import com.wrbug.polymarketbot.dto.AutoBettingSignalRequest
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class AutoBettingQueueService(
    private val decisionService: AutoBettingDecisionService,
    private val executionService: AutoBettingExecutionService
) {
    fun executeQueuedCrownSignal(
        request: AutoBettingQueuedCrownExecutionRequest,
        now: Long = System.currentTimeMillis()
    ): List<AutoBettingDecisionDto> {
        val accounts = request.accounts
            .filter { it.bettingEnabled }
            .filter { it.accountKey.trim().isNotBlank() }
        if (accounts.isEmpty()) return emptyList()

        val total = accounts.size
        return accounts.mapIndexed { index, account ->
            val signalDecision = decisionService.createIntent(
                request.toSignalRequest(account, index + 1, total),
                now
            )
            val intentId = signalDecision.id
            if (signalDecision.status != STATUS_READY || intentId == null) {
                signalDecision
            } else {
                executionService.executeCrownIntent(
                    intentId = intentId,
                    request = AutoBettingExecutionRequest(
                        profileId = account.profileId,
                        loginUrl = account.loginUrl,
                        minimumTargetOdds = request.executionMinimumTargetOdds()
                    ),
                    now = now
                )
            }
        }
    }

    private fun AutoBettingQueuedCrownExecutionRequest.toSignalRequest(
        account: AutoBettingCrownQueueAccountRequest,
        queuePosition: Int,
        queueTotal: Int
    ) = AutoBettingSignalRequest(
        signalSource = signalSource,
        accountKey = account.accountKey,
        accountDisplayName = account.accountDisplayName,
        bettingMode = bettingMode,
        matchPhase = matchPhase,
        leagueName = leagueName,
        matchTitle = matchTitle,
        marketType = marketType,
        lineValue = lineValue,
        selectionName = selectionName,
        referenceSourceKey = referenceSourceKey,
        targetSourceKey = targetSourceKey,
        referenceOdds = referenceOdds,
        targetOdds = targetOdds,
        minimumTargetOdds = minimumTargetOdds,
        oddsChangeDirection = oddsChangeDirection,
        stakeAmount = stakeAmount,
        accountStakeLimit = accountStakeLimit,
        capturedAt = capturedAt,
        maxSignalAgeSeconds = maxSignalAgeSeconds,
        queuePosition = queuePosition,
        queueTotal = queueTotal
    )

    private fun AutoBettingQueuedCrownExecutionRequest.executionMinimumTargetOdds(): BigDecimal {
        val configuredMinimum = minimumTargetOdds ?: return targetOdds
        return configuredMinimum.max(targetOdds)
    }

    private companion object {
        const val STATUS_READY = "ready"
    }
}
