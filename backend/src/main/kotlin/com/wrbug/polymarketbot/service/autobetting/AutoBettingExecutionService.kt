package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.AutoBettingDecisionDto
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class AutoBettingExecutionRequest(
    val profileId: String,
    val loginUrl: String? = null,
    val oddsTolerance: BigDecimal = BigDecimal("0.02")
)

data class CrownBetPlacementCommand(
    val profileId: String,
    val loginUrl: String?,
    val betElementId: String,
    val stakeAmount: BigDecimal,
    val targetOdds: BigDecimal,
    val oddsTolerance: BigDecimal,
    val lineValue: String?
)

data class CrownBetPlacementResult(
    val placed: Boolean,
    val historyVerified: Boolean,
    val ticketReference: String?,
    val message: String,
    val currentOdds: BigDecimal? = null
)

interface CrownBetPlacementGateway {
    fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult
}

@Service
class AutoBettingExecutionService(
    private val intentRepository: AutoBettingIntentRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository,
    private val objectMapper: ObjectMapper,
    private val crownBetPlacementGateway: CrownBetPlacementGateway
) {
    @Synchronized
    fun executeCrownIntent(
        intentId: Long,
        request: AutoBettingExecutionRequest,
        now: Long = System.currentTimeMillis()
    ): AutoBettingDecisionDto {
        val intent = intentRepository.findById(intentId).orElse(null)
            ?: return missingIntent(intentId)
        if (intent.status == STATUS_PLACED && intent.crownHistoryVerified) {
            return intent.toDecision("crown_history_verified")
        }
        if (intent.status == STATUS_PLACED_UNVERIFIED) {
            return intent.toDecision(intent.rejectReason ?: "crown_history_unverified")
        }
        if (intent.status != STATUS_READY) {
            return intent.toDecision(intent.rejectReason ?: "intent_not_ready")
        }

        val profileId = request.profileId.trim()
        if (profileId.isBlank()) {
            return reject(intent, "profile_id_required", now)
        }

        val placing = intentRepository.save(intent.copy(status = STATUS_PLACING, updatedAt = now))
        val command = buildCommand(placing, request)
            ?: return reject(placing, "crown_market_not_found", now)
        val placement = try {
            crownBetPlacementGateway.placeBet(command)
        } catch (_: Exception) {
            return reject(placing, "crown_execution_error", now)
        }

        if (placement.placed && placement.historyVerified) {
            val placed = intentRepository.save(
                placing.copy(
                    status = STATUS_PLACED,
                    rejectReason = null,
                    crownHistoryVerified = true,
                    crownHistoryCheckedAt = now,
                    crownBetReference = placement.ticketReference,
                    updatedAt = now
                )
            )
            return placed.toDecision("crown_history_verified")
        }

        if (placement.placed) {
            val reason = shortReason(placement.message.ifBlank { "crown_history_unverified" })
            val unverified = intentRepository.save(
                placing.copy(
                    status = STATUS_PLACED_UNVERIFIED,
                    rejectReason = reason,
                    crownHistoryVerified = false,
                    crownHistoryCheckedAt = now,
                    crownBetReference = placement.ticketReference,
                    updatedAt = now
                )
            )
            return unverified.toDecision(reason)
        }

        return reject(placing, shortReason(placement.message.ifBlank { "crown_bet_failed" }), now)
    }

    private fun buildCommand(intent: AutoBettingIntent, request: AutoBettingExecutionRequest): CrownBetPlacementCommand? {
        val teams = splitMatchTitle(intent.matchTitle) ?: return null
        val match = platformMatchRepository
            .findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                intent.leagueName,
                teams.first,
                teams.second
            ) ?: return null

        val betElementId = buildBetElementId(intent, match) ?: return null
        return CrownBetPlacementCommand(
            profileId = request.profileId.trim(),
            loginUrl = request.loginUrl?.trim()?.takeIf { it.isNotBlank() },
            betElementId = betElementId,
            stakeAmount = intent.stakeAmount.setScale(4, RoundingMode.HALF_UP),
            targetOdds = intent.targetOdds.setScale(8, RoundingMode.HALF_UP),
            oddsTolerance = request.oddsTolerance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
            lineValue = intent.lineValue
        )
    }

    private fun buildBetElementId(intent: AutoBettingIntent, match: OddsPlatformMatch): String? {
        val raw = runCatching { objectMapper.readTree(match.rawPayloadJson.orEmpty()) }.getOrNull()
        val gid = raw?.path("gid")?.textOrNull() ?: match.sourceMatchId.takeIf { it.isNotBlank() } ?: return null
        val ecid = raw?.path("ecid")?.textOrNull() ?: return null
        val suffix = when (intent.marketType.lowercase(Locale.ROOT)) {
            "handicap" -> handicapSuffix(intent, match)
            "total" -> totalSuffix(intent.selectionName)
            else -> null
        } ?: return null
        return "bet_${gid}_${ecid}_${suffix}"
    }

    private fun handicapSuffix(intent: AutoBettingIntent, match: OddsPlatformMatch): String? {
        val selection = intent.selectionName.trim()
        return when {
            selection.equals(match.rawHomeTeam, ignoreCase = true) -> "REH"
            selection.equals(match.rawAwayTeam, ignoreCase = true) -> "REC"
            selection.equals("home", ignoreCase = true) || selection == "主队" -> "REH"
            selection.equals("away", ignoreCase = true) || selection == "客队" -> "REC"
            else -> null
        }
    }

    private fun totalSuffix(selectionName: String): String? {
        val selection = selectionName.trim().lowercase(Locale.ROOT)
        return when {
            selection.contains("大") || selection == "over" || selection == "o" -> "ROUC"
            selection.contains("小") || selection == "under" || selection == "u" -> "ROUH"
            else -> null
        }
    }

    private fun splitMatchTitle(matchTitle: String): Pair<String, String>? {
        val parts = matchTitle.split(Regex("""\s+vs\s+|\s+v\s+""", RegexOption.IGNORE_CASE), limit = 2)
            .map { it.trim() }
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return parts[0] to parts[1]
    }

    private fun reject(intent: AutoBettingIntent, reason: String, now: Long): AutoBettingDecisionDto {
        val rejected = intentRepository.save(
            intent.copy(
                status = STATUS_REJECTED,
                rejectReason = shortReason(reason),
                activeDedupeKey = null,
                updatedAt = now
            )
        )
        return rejected.toDecision(shortReason(reason))
    }

    private fun missingIntent(intentId: Long) = AutoBettingDecisionDto(
        id = intentId,
        status = STATUS_REJECTED,
        reason = "intent_not_found",
        dedupeKey = "",
        signalSource = "",
        bettingMode = "",
        matchPhase = "",
        accountKey = "",
        leagueName = "",
        matchTitle = "",
        marketType = "",
        lineValue = null,
        selectionName = "",
        referenceSourceKey = "",
        targetSourceKey = "",
        referenceOdds = BigDecimal.ZERO,
        targetOdds = BigDecimal.ZERO,
        targetDecimalOdds = BigDecimal.ZERO,
        decimalEdge = BigDecimal.ZERO,
        stakeAmount = BigDecimal.ZERO,
        capturedAt = 0,
        createdAt = 0
    )

    private fun AutoBettingIntent.toDecision(reason: String): AutoBettingDecisionDto {
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
            crownBetReference = crownBetReference
        )
    }

    private fun com.fasterxml.jackson.databind.JsonNode.textOrNull(): String? {
        return takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }

    private fun shortReason(reason: String): String {
        return reason.trim().take(64).ifBlank { "crown_bet_failed" }
    }

    companion object {
        private const val STATUS_READY = "ready"
        private const val STATUS_PLACING = "placing"
        private const val STATUS_PLACED_UNVERIFIED = "placed_unverified"
        private const val STATUS_PLACED = "placed"
        private const val STATUS_REJECTED = "rejected"
    }
}
