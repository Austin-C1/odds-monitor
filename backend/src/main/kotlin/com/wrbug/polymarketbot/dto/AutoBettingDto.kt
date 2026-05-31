package com.wrbug.polymarketbot.dto

import java.math.BigDecimal

data class AutoBettingSignalRequest(
    val signalSource: String = "odds_monitor",
    val accountKey: String? = null,
    val accountDisplayName: String? = null,
    val bettingMode: String,
    val matchPhase: String,
    val leagueName: String,
    val matchTitle: String,
    val marketType: String,
    val lineValue: String? = null,
    val selectionName: String,
    val referenceSourceKey: String,
    val targetSourceKey: String,
    val referenceOdds: BigDecimal,
    val targetOdds: BigDecimal,
    val minimumTargetOdds: BigDecimal? = null,
    val oddsChangeDirection: String? = null,
    val stakeAmount: BigDecimal,
    val accountStakeLimit: BigDecimal? = null,
    val capturedAt: Long,
    val maxSignalAgeSeconds: Long? = null,
    val queuePosition: Int? = null,
    val queueTotal: Int? = null
)

data class AutoBettingCrownQueueAccountRequest(
    val accountKey: String,
    val accountDisplayName: String? = null,
    val profileId: String,
    val loginUrl: String? = null,
    val bettingEnabled: Boolean = true
)

data class AutoBettingQueuedCrownExecutionRequest(
    val signalSource: String = "odds_monitor",
    val bettingMode: String,
    val matchPhase: String,
    val leagueName: String,
    val matchTitle: String,
    val marketType: String,
    val lineValue: String? = null,
    val selectionName: String,
    val referenceSourceKey: String,
    val targetSourceKey: String,
    val referenceOdds: BigDecimal,
    val targetOdds: BigDecimal,
    val minimumTargetOdds: BigDecimal? = null,
    val oddsChangeDirection: String? = null,
    val stakeAmount: BigDecimal,
    val accountStakeLimit: BigDecimal? = null,
    val capturedAt: Long,
    val maxSignalAgeSeconds: Long? = null,
    val queuePosition: Int? = null,
    val queueTotal: Int? = null,
    val accounts: List<AutoBettingCrownQueueAccountRequest> = emptyList()
)

data class AutoBettingDecisionDto(
    val id: Long? = null,
    val status: String,
    val reason: String,
    val dedupeKey: String,
    val signalSource: String,
    val bettingMode: String,
    val matchPhase: String,
    val accountKey: String,
    val accountDisplayName: String? = null,
    val leagueName: String,
    val matchTitle: String,
    val marketType: String,
    val lineValue: String?,
    val selectionName: String,
    val referenceSourceKey: String,
    val targetSourceKey: String,
    val referenceOdds: BigDecimal,
    val targetOdds: BigDecimal,
    val targetDecimalOdds: BigDecimal,
    val decimalEdge: BigDecimal,
    val stakeAmount: BigDecimal,
    val capturedAt: Long,
    val createdAt: Long,
    val crownHistoryVerified: Boolean = false,
    val crownHistoryCheckedAt: Long? = null,
    val crownBetReference: String? = null,
    val queuePosition: Int? = null,
    val queueTotal: Int? = null
)

data class AdsPowerStatusDto(
    val available: Boolean,
    val baseUrl: String,
    val code: Int? = null,
    val message: String,
    val checkedAt: Long
)

data class AdsPowerStartProfileRequest(
    val profileId: String
)

data class AdsPowerCrownSessionRequest(
    val profileId: String,
    val loginName: String? = null,
    val loginUrl: String? = null
)

data class AdsPowerCrownSessionMatchRequest(
    val loginName: String,
    val loginUrl: String? = null,
    val preferredProfileId: String? = null
)

data class AdsPowerBrowserSessionDto(
    val profileId: String,
    val opened: Boolean,
    val message: String,
    val debugPort: String? = null,
    val openedAt: Long
)

data class AdsPowerProfileActiveDto(
    val profileId: String,
    val opened: Boolean,
    val message: String,
    val status: String? = null,
    val debugPort: String? = null,
    val checkedAt: Long
)

data class AdsPowerCrownSessionDto(
    val profileId: String,
    val opened: Boolean,
    val loggedIn: Boolean,
    val accountStatus: String,
    val balance: BigDecimal? = null,
    val currency: String = "CNY",
    val pageUrl: String? = null,
    val message: String,
    val debugPort: String? = null,
    val checkedAt: Long
)

data class AdsPowerCrownSessionCandidateDto(
    val profileId: String,
    val profileSerialNumber: String? = null,
    val profileName: String? = null,
    val profileUsername: String? = null,
    val remark: String? = null,
    val pageLoginName: String? = null,
    val opened: Boolean,
    val loggedIn: Boolean,
    val accountStatus: String,
    val balance: BigDecimal? = null,
    val currency: String = "CNY",
    val pageUrl: String? = null,
    val message: String,
    val debugPort: String? = null,
    val checkedAt: Long
)
