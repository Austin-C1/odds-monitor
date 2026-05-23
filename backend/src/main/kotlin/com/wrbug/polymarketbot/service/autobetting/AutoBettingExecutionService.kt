package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.AutoBettingDecisionDto
import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.service.oddsmonitor.OddsMatchCandidate
import com.wrbug.polymarketbot.service.oddsmonitor.OddsMatchMatcher
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class AutoBettingExecutionRequest(
    val profileId: String,
    val loginUrl: String? = null,
    val minimumTargetOdds: BigDecimal? = null
)

data class CrownBetPlacementCommand(
    val profileId: String,
    val loginUrl: String?,
    val matchPhase: String = "live",
    val matchTitle: String = "",
    val marketType: String = "",
    val selectionName: String = "",
    val betElementId: String,
    val stakeAmount: BigDecimal,
    val targetOdds: BigDecimal,
    val lineValue: String?
)

data class CrownBetPlacementResult(
    val placed: Boolean,
    val historyVerified: Boolean,
    val ticketReference: String?,
    val message: String,
    val currentOdds: BigDecimal? = null
)

private data class CrownMatchResolution(
    val match: OddsPlatformMatch,
    val reversed: Boolean
)

private data class CrownMatchResolutionCandidate(
    val match: OddsPlatformMatch,
    val reversed: Boolean,
    val score: Double
)

private data class CrownMatchLookupResult(
    val resolution: CrownMatchResolution?,
    val rejectReason: String = "crown_market_not_found"
)

private data class CrownCommandBuildResult(
    val command: CrownBetPlacementCommand?,
    val rejectReason: String = "crown_market_not_found"
)

private enum class CrownPhaseMatchState {
    MATCH,
    MISMATCH,
    UNKNOWN
}

interface CrownBetPlacementGateway {
    fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult

    fun verifyPlacedBet(command: CrownBetPlacementCommand, ticketReference: String?): CrownBetPlacementResult {
        return CrownBetPlacementResult(
            placed = true,
            historyVerified = false,
            ticketReference = ticketReference,
            message = "crown_history_unverified"
        )
    }
}

@Service
class AutoBettingExecutionService(
    private val intentRepository: AutoBettingIntentRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository,
    private val objectMapper: ObjectMapper,
    private val crownBetPlacementGateway: CrownBetPlacementGateway,
    private val profileExecutionLock: CrownProfileExecutionLock = CrownProfileExecutionLock()
) {
    private val staleReadyTimeoutMillis = 180_000L
    private val stalePlacingTimeoutMillis = 30_000L
    private val unverifiedRecheckDelayMillis = 30_000L

    fun executeCrownIntent(
        intentId: Long,
        request: AutoBettingExecutionRequest,
        now: Long = System.currentTimeMillis()
    ): AutoBettingDecisionDto {
        rejectStaleExecutionIntents(now)
        val intent = intentRepository.findById(intentId).orElse(null)
            ?: return missingIntent(intentId)
        if (intent.status == STATUS_PLACED && intent.crownHistoryVerified) {
            return intent.toDecision("crown_history_verified")
        }
        if (intent.status == STATUS_PLACED_UNVERIFIED) {
            return recheckUnverifiedCrownIntent(intent, request, now)
        }
        if (intent.status != STATUS_READY) {
            return intent.toDecision(intent.rejectReason ?: "intent_not_ready")
        }

        val profileId = request.profileId.trim()
        if (profileId.isBlank()) {
            return reject(intent, "profile_id_required", now)
        }

        val markedPlacing = intentRepository.markReadyIntentPlacingById(
            intentId = intent.id ?: return missingIntent(intentId),
            readyStatus = STATUS_READY,
            placingStatus = STATUS_PLACING,
            updatedAt = now
        )
        if (markedPlacing != 1) {
            val latest = intentRepository.findById(intentId).orElse(intent)
            return latest.toDecision(latest.rejectReason ?: "intent_not_ready")
        }
        val placing = intentRepository.save(intent.copy(status = STATUS_PLACING, updatedAt = now))
        val commandResult = buildCommand(placing, request)
        val command = commandResult.command
            ?: return reject(placing, commandResult.rejectReason, now)
        val placement = try {
            profileExecutionLock.withProfileLock(profileId) {
                crownBetPlacementGateway.placeBet(command)
            }
        } catch (_: Exception) {
            return reject(placing, "crown_execution_error", now)
        }

        if (placement.placed && placement.historyVerified) {
            val reason = shortReason(placement.message.ifBlank { "crown_history_verified" })
            val placed = intentRepository.save(
                placing.copy(
                    status = STATUS_PLACED,
                    rejectReason = null,
                    activeDedupeKey = placing.dedupeKey,
                    crownHistoryVerified = true,
                    crownHistoryCheckedAt = now,
                    crownBetReference = placement.ticketReference,
                    updatedAt = now
                )
            )
            return placed.toDecision(reason)
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

    private fun recheckUnverifiedCrownIntent(
        intent: AutoBettingIntent,
        request: AutoBettingExecutionRequest,
        now: Long
    ): AutoBettingDecisionDto {
        val lastCheckedAt = intent.crownHistoryCheckedAt ?: intent.updatedAt
        if (now - lastCheckedAt < unverifiedRecheckDelayMillis) {
            return intent.toDecision("crown_history_recheck_pending")
        }

        val profileId = request.profileId.trim()
        if (profileId.isBlank()) {
            return intent.toDecision("profile_id_required")
        }

        val placing = intentRepository.save(intent.copy(status = STATUS_PLACING, updatedAt = now))
        val commandResult = buildCommand(placing, request)
        val command = commandResult.command
        if (command == null) {
            val unverified = intentRepository.save(
                placing.copy(
                    status = STATUS_PLACED_UNVERIFIED,
                    rejectReason = commandResult.rejectReason,
                    crownHistoryVerified = false,
                    crownHistoryCheckedAt = now,
                    updatedAt = now
                )
            )
            return unverified.toDecision(commandResult.rejectReason)
        }

        val verification = try {
            profileExecutionLock.withProfileLock(profileId) {
                crownBetPlacementGateway.verifyPlacedBet(command, placing.crownBetReference)
            }
        } catch (_: Exception) {
            CrownBetPlacementResult(
                placed = true,
                historyVerified = false,
                ticketReference = placing.crownBetReference,
                message = "crown_history_recheck_failed"
            )
        }

        if (verification.placed && verification.historyVerified) {
            val reason = shortReason(verification.message.ifBlank { "crown_history_verified" })
            val placed = intentRepository.save(
                placing.copy(
                    status = STATUS_PLACED,
                    rejectReason = null,
                    activeDedupeKey = placing.dedupeKey,
                    crownHistoryVerified = true,
                    crownHistoryCheckedAt = now,
                    crownBetReference = verification.ticketReference ?: placing.crownBetReference,
                    updatedAt = now
                )
            )
            return placed.toDecision(reason)
        }

        val reason = shortReason(verification.message.ifBlank { "crown_history_unverified" })
        val unverified = intentRepository.save(
            placing.copy(
                status = STATUS_PLACED_UNVERIFIED,
                rejectReason = reason,
                crownHistoryVerified = false,
                crownHistoryCheckedAt = now,
                crownBetReference = verification.ticketReference ?: placing.crownBetReference,
                updatedAt = now
            )
        )
        return unverified.toDecision(reason)
    }

    private fun buildCommand(intent: AutoBettingIntent, request: AutoBettingExecutionRequest): CrownCommandBuildResult {
        val teams = splitMatchTitle(intent.matchTitle)
            ?: return CrownCommandBuildResult(null, "crown_market_not_found")
        val matchLookup = resolveCrownMatch(intent, teams)
        val resolvedMatch = matchLookup.resolution
            ?: return CrownCommandBuildResult(null, matchLookup.rejectReason)

        val betElementId = buildBetElementId(intent, resolvedMatch)
            ?: return CrownCommandBuildResult(null, "crown_market_not_found")
        return CrownCommandBuildResult(
            CrownBetPlacementCommand(
                profileId = request.profileId.trim(),
                loginUrl = request.loginUrl?.trim()?.takeIf { it.isNotBlank() },
                matchPhase = intent.matchPhase.trim().lowercase(Locale.ROOT),
                matchTitle = "${resolvedMatch.match.rawHomeTeam} vs ${resolvedMatch.match.rawAwayTeam}",
                marketType = intent.marketType,
                selectionName = intent.selectionName,
                betElementId = betElementId,
                stakeAmount = intent.stakeAmount.setScale(4, RoundingMode.HALF_UP),
                targetOdds = (request.minimumTargetOdds ?: intent.targetOdds).setScale(8, RoundingMode.HALF_UP),
                lineValue = intent.lineValue
            )
        )
    }

    private fun resolveCrownMatch(intent: AutoBettingIntent, teams: Pair<String, String>): CrownMatchLookupResult {
        val phase = intent.matchPhase.trim().lowercase(Locale.ROOT)
        if (phase != "prematch" && phase != "live") {
            return CrownMatchLookupResult(null, "crown_phase_unknown")
        }
        val exactMatch = platformMatchRepository
            .findTop1BySourceKeyAndRawLeagueNameAndRawHomeTeamAndRawAwayTeamOrderByUpdatedAtDesc(
                "crown",
                intent.leagueName,
                teams.first,
                teams.second
            )

        val recentCrownMatches = platformMatchRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown").orEmpty()
        val signalCandidate = OddsMatchCandidate(
            id = null,
            leagueName = intent.leagueName,
            homeTeam = teams.first,
            awayTeam = teams.second,
            startTime = null
        )

        val candidates = mutableListOf<CrownMatchResolutionCandidate>()
        if (exactMatch != null) {
            candidates += CrownMatchResolutionCandidate(exactMatch, reversed = false, score = Double.MAX_VALUE)
        }
        candidates += recentCrownMatches
            .asSequence()
            .map { match ->
                val score = OddsMatchMatcher.score(
                    signalCandidate,
                    OddsMatchCandidate(
                        id = match.id,
                        leagueName = match.rawLeagueName,
                        homeTeam = match.rawHomeTeam,
                        awayTeam = match.rawAwayTeam,
                        startTime = match.rawStartTime
                    )
                )
                match to score
            }
            .filter { (_, score) -> OddsMatchMatcher.shouldMerge(score) }
            .map { (match, score) -> CrownMatchResolutionCandidate(match, score.reversed, score.score) }
            .toList()

        val distinctCandidates = candidates
            .distinctBy { it.match.id ?: "${it.match.sourceKey}:${it.match.sourceMatchId}:${it.match.rawLeagueName}:${it.match.rawHomeTeam}:${it.match.rawAwayTeam}" }

        val verifiedPhaseMatch = distinctCandidates
            .filter { phaseMatchState(it.match, phase) == CrownPhaseMatchState.MATCH }
            .maxByOrNull { it.score }

        if (verifiedPhaseMatch != null) {
            return CrownMatchLookupResult(
                CrownMatchResolution(verifiedPhaseMatch.match, verifiedPhaseMatch.reversed)
            )
        }

        if (distinctCandidates.any { phaseMatchState(it.match, phase) == CrownPhaseMatchState.UNKNOWN }) {
            return CrownMatchLookupResult(null, "crown_phase_unknown")
        }
        if (distinctCandidates.any { phaseMatchState(it.match, phase) == CrownPhaseMatchState.MISMATCH }) {
            return CrownMatchLookupResult(null, "crown_phase_mismatch")
        }

        return CrownMatchLookupResult(null, "crown_market_not_found")
    }

    private fun phaseMatchState(match: OddsPlatformMatch, phase: String): CrownPhaseMatchState {
        if (phase != "prematch" && phase != "live") return CrownPhaseMatchState.UNKNOWN
        val matchPhase = crownMatchPhase(match) ?: return CrownPhaseMatchState.UNKNOWN
        return if (matchPhase == phase) CrownPhaseMatchState.MATCH else CrownPhaseMatchState.MISMATCH
    }

    private fun crownMatchPhase(match: OddsPlatformMatch): String? {
        val raw = runCatching { objectMapper.readTree(match.rawPayloadJson.orEmpty()) }.getOrNull() ?: return null
        raw.path("is_live")
            .takeIf { !it.isMissingNode && !it.isNull && it.isBoolean }
            ?.let { return if (it.asBoolean()) "live" else "prematch" }

        raw.path("showtype").textOrNull()?.trim()?.lowercase(Locale.ROOT)?.let { showType ->
            if (showType in setOf("live", "rb", "inplay", "in-play")) return "live"
            if (showType in setOf("today", "early", "prematch")) return "prematch"
        }

        raw.path("isRB").textOrNull()?.trim()?.let { isRb ->
            if (isRb.equals("Y", ignoreCase = true)) return "live"
            if (isRb.equals("N", ignoreCase = true)) return "prematch"
        }

        raw.path("retimeset").textOrNull()?.trim()?.takeIf { it.isNotBlank() && it != "0" }?.let {
            return "live"
        }

        return null
    }

    private fun buildBetElementId(intent: AutoBettingIntent, resolution: CrownMatchResolution): String? {
        val match = resolution.match
        val raw = runCatching { objectMapper.readTree(match.rawPayloadJson.orEmpty()) }.getOrNull()
        val gid = raw?.path("gid")?.textOrNull() ?: match.sourceMatchId.takeIf { it.isNotBlank() } ?: return null
        val ecid = raw?.path("ecid")?.textOrNull() ?: return null
        val phase = intent.matchPhase.trim().lowercase(Locale.ROOT)
        val suffix = when (intent.marketType.lowercase(Locale.ROOT)) {
            "handicap" -> handicapSuffix(intent, match, resolution.reversed, phase)
            "total" -> totalSuffix(intent.selectionName, phase)
            else -> null
        } ?: return null
        return "bet_${gid}_${ecid}_${suffix}"
    }

    private fun handicapSuffix(
        intent: AutoBettingIntent,
        match: OddsPlatformMatch,
        reversed: Boolean,
        phase: String
    ): String? {
        val selection = intent.selectionName.trim()
        val intentTeams = splitMatchTitle(intent.matchTitle)
        val homeSuffix = if (phase == "prematch") "RH" else "REH"
        val awaySuffix = if (phase == "prematch") "RC" else "REC"
        return when {
            sameTeamName(selection, match.rawHomeTeam) -> homeSuffix
            sameTeamName(selection, match.rawAwayTeam) -> awaySuffix
            intentTeams != null && sameTeamName(selection, intentTeams.first) -> if (reversed) awaySuffix else homeSuffix
            intentTeams != null && sameTeamName(selection, intentTeams.second) -> if (reversed) homeSuffix else awaySuffix
            selection.equals("home", ignoreCase = true) -> if (reversed) awaySuffix else homeSuffix
            selection.equals("away", ignoreCase = true) -> if (reversed) homeSuffix else awaySuffix
            selection == "主队" -> if (reversed) awaySuffix else homeSuffix
            selection == "客队" -> if (reversed) homeSuffix else awaySuffix
            else -> null
        }
    }

    private fun sameTeamName(left: String, right: String): Boolean {
        val normalizedLeft = TextEncodingUtils.repairMojibake(left)
            .replace(Regex("""\s+"""), "")
            .trim()
        val normalizedRight = TextEncodingUtils.repairMojibake(right)
            .replace(Regex("""\s+"""), "")
            .trim()
        return normalizedLeft.isNotBlank() && normalizedLeft.equals(normalizedRight, ignoreCase = true)
    }

    private fun totalSuffix(selectionName: String, phase: String): String? {
        val selection = selectionName.trim().lowercase(Locale.ROOT)
        val overSuffix = if (phase == "prematch") "OUC" else "ROUC"
        val underSuffix = if (phase == "prematch") "OUH" else "ROUH"
        return when {
            selection.contains("大") || selection == "over" || selection == "o" -> overSuffix
            selection.contains("小") || selection == "under" || selection == "u" -> underSuffix
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
