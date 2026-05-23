package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.dto.AutoBettingDecisionDto
import com.wrbug.polymarketbot.dto.AutoBettingSignalRequest
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@Service
class AutoBettingDecisionService(
    private val intentRepository: AutoBettingIntentRepository
) {
    private val lockedStatuses = listOf("ready", "placing", "placed", "placed_unverified")
    private val supportedPhases = setOf("prematch", "live")
    private val supportedMarkets = setOf("handicap", "total")
    private val defaultMaxSignalAgeMillis = 30_000L
    private val maxConfigurableSignalAgeSeconds = 3_600L
    private val maxSignalFutureSkewMillis = 5_000L
    private val maxSingleStake = BigDecimal("500.00")
    private val staleReadyTimeoutMillis = 180_000L
    private val stalePlacingTimeoutMillis = 30_000L

    @Synchronized
    fun createIntent(request: AutoBettingSignalRequest, now: Long = System.currentTimeMillis()): AutoBettingDecisionDto {
        val normalized = normalize(request)
        rejectStaleExecutionIntents(now)
        val targetDecimalOdds = normalizeTargetDecimalOdds(normalized.targetSourceKey, normalized.targetOdds)
        val decimalEdge = decimalEdge(normalized, targetDecimalOdds)
        val dedupeKey = dedupeKey(normalized)
        val decision = decide(normalized, dedupeKey, now)
        val intent = intentRepository.save(
            AutoBettingIntent(
                dedupeKey = dedupeKey,
                activeDedupeKey = null,
                signalSource = normalized.signalSource,
                bettingMode = normalized.bettingMode,
                matchPhase = normalized.matchPhase,
                accountKey = normalized.accountKey ?: DEFAULT_ACCOUNT_KEY,
                leagueName = normalized.leagueName,
                matchTitle = normalized.matchTitle,
                marketType = normalized.marketType,
                lineValue = normalized.lineValue,
                selectionName = normalized.selectionName,
                referenceSourceKey = normalized.referenceSourceKey,
                targetSourceKey = normalized.targetSourceKey,
                referenceOdds = scaleOdds(normalized.referenceOdds),
                targetOdds = scaleOdds(normalized.targetOdds),
                targetDecimalOdds = targetDecimalOdds,
                decimalEdge = decimalEdge,
                stakeAmount = normalized.stakeAmount.setScale(4, RoundingMode.HALF_UP),
                status = decision.status,
                rejectReason = decision.reason.takeIf { decision.status == STATUS_REJECTED },
                capturedAt = normalized.capturedAt,
                createdAt = now,
                updatedAt = now
            )
        )
        return intent.toDecision(
            decision.reason,
            queuePosition = normalized.queuePosition,
            queueTotal = normalized.queueTotal
        )
    }

    fun listRecentIntents(): List<AutoBettingDecisionDto> {
        return intentRepository.findTop100ByOrderByCreatedAtDesc()
            .map { intent ->
                val reason = intent.rejectReason ?: if (intent.status == STATUS_READY) "accepted" else intent.status
                intent.toDecision(reason)
            }
    }

    fun listRecentVerifiedPlacedIntents(): List<AutoBettingDecisionDto> {
        return intentRepository.findTop100ByStatusAndCrownHistoryVerifiedTrueOrderByCreatedAtDesc(STATUS_PLACED)
            .map { intent -> intent.toDecision("crown_history_verified") }
    }

    private fun decide(
        request: AutoBettingSignalRequest,
        dedupeKey: String,
        now: Long
    ): Decision {
        if (request.signalSource != "odds_monitor") {
            return Decision(STATUS_REJECTED, "unsupported_signal_source")
        }
        if (request.bettingMode !in supportedPhases) {
            return Decision(STATUS_REJECTED, "unsupported_betting_mode")
        }
        if (request.matchPhase !in supportedPhases) {
            return Decision(STATUS_REJECTED, "unsupported_match_phase")
        }
        if (request.bettingMode != request.matchPhase) {
            return Decision(STATUS_REJECTED, "phase_mismatch")
        }
        if (request.referenceSourceKey !in supportedReferenceSources) {
            return Decision(STATUS_REJECTED, "unsupported_reference_source")
        }
        if (request.targetSourceKey != "crown") {
            return Decision(STATUS_REJECTED, "unsupported_target_source")
        }
        if (request.leagueName.isBlank() || request.matchTitle.isBlank() || request.selectionName.isBlank()) {
            return Decision(STATUS_REJECTED, "invalid_signal_content")
        }
        if (request.referenceOdds <= BigDecimal.ZERO || request.targetOdds <= BigDecimal.ZERO) {
            return Decision(STATUS_REJECTED, "invalid_odds")
        }
        rejectStaleReadyIntent(dedupeKey, request, now)
        if (request.minimumTargetOdds != null && request.targetOdds < request.minimumTargetOdds) {
            return Decision(STATUS_REJECTED, "target_odds_below_minimum")
        }
        if (request.capturedAt - now > maxSignalFutureSkewMillis) {
            return Decision(STATUS_REJECTED, "future_signal")
        }
        if (now - request.capturedAt > maxSignalAgeMillis(request.maxSignalAgeSeconds)) {
            return Decision(STATUS_REJECTED, "stale_signal")
        }
        if (request.marketType !in supportedMarkets) {
            return Decision(STATUS_REJECTED, "unsupported_market")
        }
        if (request.stakeAmount <= BigDecimal.ZERO) {
            return Decision(STATUS_REJECTED, "invalid_stake")
        }
        if (request.stakeAmount > maxSingleStake) {
            return Decision(STATUS_REJECTED, "stake_over_single_limit")
        }
        val existingIntent = intentRepository.findTopByDedupeKeyAndStatusInOrderByCreatedAtDesc(dedupeKey, lockedStatuses)
        if (existingIntent?.status in listOf(STATUS_PLACED, STATUS_PLACED_UNVERIFIED)) {
            return Decision(STATUS_REJECTED, "duplicate_placed_intent")
        }
        if (existingIntent != null || intentRepository.existsByDedupeKeyAndStatusIn(dedupeKey, lockedStatuses)) {
            return Decision(STATUS_REJECTED, "duplicate_active_intent")
        }
        return Decision(STATUS_READY, "accepted")
    }

    private fun normalize(request: AutoBettingSignalRequest): AutoBettingSignalRequest {
        return request.copy(
            signalSource = request.signalSource.trim().lowercase(Locale.ROOT),
            accountKey = request.accountKey?.trim()?.takeIf { it.isNotBlank() },
            bettingMode = request.bettingMode.trim().lowercase(Locale.ROOT),
            matchPhase = request.matchPhase.trim().lowercase(Locale.ROOT),
            leagueName = request.leagueName.trim(),
            matchTitle = request.matchTitle.trim(),
            marketType = request.marketType.trim().lowercase(Locale.ROOT),
            lineValue = request.lineValue?.trim()?.takeIf { it.isNotBlank() },
            selectionName = request.selectionName.trim().lowercase(Locale.ROOT),
            referenceSourceKey = request.referenceSourceKey.trim().lowercase(Locale.ROOT),
            targetSourceKey = request.targetSourceKey.trim().lowercase(Locale.ROOT),
            oddsChangeDirection = request.oddsChangeDirection?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() },
            queuePosition = normalizedQueuePosition(request.queuePosition, request.queueTotal),
            queueTotal = normalizedQueueTotal(request.queuePosition, request.queueTotal)
        )
    }

    private fun normalizedQueuePosition(queuePosition: Int?, queueTotal: Int?): Int? {
        val position = queuePosition?.takeIf { it > 0 } ?: return null
        val total = queueTotal?.takeIf { it > 0 }
        return if (total == null || position <= total) position else null
    }

    private fun normalizedQueueTotal(queuePosition: Int?, queueTotal: Int?): Int? {
        val total = queueTotal?.takeIf { it > 0 } ?: return null
        val position = queuePosition?.takeIf { it > 0 }
        return if (position == null || position <= total) total else null
    }

    private fun normalizeTargetDecimalOdds(sourceKey: String, odds: BigDecimal): BigDecimal {
        val decimalOdds = if (sourceKey == "crown") {
            odds.add(BigDecimal.ONE)
        } else {
            odds
        }
        return scaleOdds(decimalOdds)
    }

    private fun decimalEdge(request: AutoBettingSignalRequest, targetDecimalOdds: BigDecimal): BigDecimal {
        val edge = if (request.referenceSourceKey == "crown" && request.targetSourceKey == "crown") {
            when (request.oddsChangeDirection) {
                "drop" -> request.referenceOdds.subtract(request.targetOdds)
                else -> request.targetOdds.subtract(request.referenceOdds)
            }
        } else {
            targetDecimalOdds.subtract(request.referenceOdds)
        }
        return scaleOdds(edge)
    }

    private fun dedupeKey(request: AutoBettingSignalRequest): String {
        val account = normalizeKeyPart(request.accountKey ?: DEFAULT_ACCOUNT_KEY, keepDash = true)
        val phase = normalizeKeyPart(request.bettingMode)
        val match = normalizeKeyPart("${request.leagueName}${request.matchTitle}")
        val market = normalizeKeyPart(request.marketType)
        val line = request.lineValue?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("""[^\p{L}\p{N}+./-]+"""), "").orEmpty()
        val selection = normalizeKeyPart(request.selectionName)
        return listOf(account, phase, match, market, line, selection).joinToString(":")
    }

    private fun maxSignalAgeMillis(maxSignalAgeSeconds: Long?): Long {
        return maxSignalAgeSeconds
            ?.coerceIn(1L, maxConfigurableSignalAgeSeconds)
            ?.times(1_000L)
            ?: defaultMaxSignalAgeMillis
    }

    private fun rejectStaleReadyIntent(dedupeKey: String, request: AutoBettingSignalRequest, now: Long) {
        intentRepository.rejectStaleReadyIntentByDedupeKey(
            dedupeKey = dedupeKey,
            readyStatus = STATUS_READY,
            capturedBefore = now - maxSignalAgeMillis(request.maxSignalAgeSeconds),
            rejectedStatus = STATUS_REJECTED,
            rejectReason = "stale_signal",
            updatedAt = now
        )
    }

    private fun rejectStaleExecutionIntents(now: Long) {
        intentRepository.rejectStaleReadyIntents(
            readyStatus = STATUS_READY,
            updatedBefore = now - staleReadyTimeoutMillis,
            rejectedStatus = STATUS_REJECTED,
            rejectReason = "stale_signal",
            updatedAt = now
        )
        intentRepository.rejectStalePlacingIntents(
            placingStatus = STATUS_PLACING,
            updatedBefore = now - stalePlacingTimeoutMillis,
            rejectedStatus = STATUS_REJECTED,
            rejectReason = "crown_execution_timeout",
            updatedAt = now
        )
    }

    private fun normalizeKeyPart(value: String, keepDash: Boolean = false): String {
        val pattern = if (keepDash) Regex("""[^\p{L}\p{N}-]+""") else Regex("""[^\p{L}\p{N}]+""")
        return value.lowercase(Locale.ROOT).replace(pattern, "")
    }

    private fun AutoBettingIntent.toDecision(
        reason: String,
        queuePosition: Int? = null,
        queueTotal: Int? = null
    ): AutoBettingDecisionDto {
        return AutoBettingDecisionDto(
            id = id,
            status = status,
            reason = reason,
            dedupeKey = dedupeKey,
            signalSource = signalSource,
            bettingMode = bettingMode,
            matchPhase = matchPhase,
            accountKey = accountKey,
            leagueName = leagueName,
            matchTitle = matchTitle,
            marketType = marketType,
            lineValue = lineValue,
            selectionName = selectionName,
            referenceSourceKey = referenceSourceKey,
            targetSourceKey = targetSourceKey,
            referenceOdds = referenceOdds,
            targetOdds = targetOdds,
            targetDecimalOdds = targetDecimalOdds,
            decimalEdge = decimalEdge,
            stakeAmount = stakeAmount,
            capturedAt = capturedAt,
            createdAt = createdAt,
            crownHistoryVerified = crownHistoryVerified,
            crownHistoryCheckedAt = crownHistoryCheckedAt,
            crownBetReference = crownBetReference,
            queuePosition = queuePosition,
            queueTotal = queueTotal
        )
    }

    private fun scaleOdds(value: BigDecimal): BigDecimal {
        return value.setScale(8, RoundingMode.HALF_UP)
    }

    private data class Decision(
        val status: String,
        val reason: String
    )

    companion object {
        const val STATUS_READY = "ready"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_PLACING = "placing"
        const val STATUS_PLACED = "placed"
        const val STATUS_PLACED_UNVERIFIED = "placed_unverified"
        private const val DEFAULT_ACCOUNT_KEY = "default"
        private val supportedReferenceSources = setOf("pinnacle", "crown")
    }
}
