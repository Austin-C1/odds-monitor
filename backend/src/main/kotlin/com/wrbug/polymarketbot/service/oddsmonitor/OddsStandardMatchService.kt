package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

@Service
class OddsStandardMatchService(
    private val matchRepository: OddsMatchRepository,
    private val linkRepository: OddsMatchLinkRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository
) {
    @Transactional
    fun resolveStandardMatch(platformMatch: OddsPlatformMatch): OddsMatch {
        val platformMatchId = platformMatch.id ?: return createStandardMatch(platformMatch)
        val existingLink = linkRepository.findByPlatformMatchId(platformMatchId)
        if (existingLink != null) {
            matchRepository.findById(existingLink.matchId).orElse(null)?.let {
                return resolveExistingLink(platformMatch, existingLink, it)
            }
        }

        val incoming = platformMatch.toCandidate()
        val candidates = matchRepository.findTop500BySportOrderByStartTimeAsc("football")
        val best = candidates
            .map { match -> match to OddsMatchMatcher.score(match.toCandidate(), incoming) }
            .maxByOrNull { (_, score) -> score.score }

        val standardMatch = if (best != null && OddsMatchMatcher.shouldMerge(best.second)) {
            refreshStandardMatchStatus(best.first, platformMatch)
        } else {
            createStandardMatch(platformMatch)
        }
        val score = best?.second?.takeIf { OddsMatchMatcher.shouldMerge(it) }
        saveLink(
            matchId = standardMatch.id ?: return standardMatch,
            platformMatchId = platformMatchId,
            confidence = score?.score ?: 1.0,
            matchMethod = score?.matchMethod ?: "new"
        )
        return standardMatch
    }

    private fun resolveExistingLink(
        platformMatch: OddsPlatformMatch,
        existingLink: OddsMatchLink,
        linkedMatch: OddsMatch
    ): OddsMatch {
        val incoming = platformMatch.toCandidate()
        val currentScore = OddsMatchMatcher.score(linkedMatch.toCandidate(), incoming)
        val linkedMatchId = linkedMatch.id
        val best = matchRepository.findTop500BySportOrderByStartTimeAsc("football")
            .asSequence()
            .filter { match -> match.id != null && match.id != linkedMatchId }
            .map { match -> match to OddsMatchMatcher.score(match.toCandidate(), incoming) }
            .filter { (_, score) -> OddsMatchMatcher.shouldMerge(score) }
            .maxByOrNull { (_, score) -> score.score }

        if (best != null && best.second.score > currentScore.score) {
            val target = refreshStandardMatchStatus(best.first, platformMatch)
            val targetId = target.id ?: return target
            linkRepository.save(
                existingLink.copy(
                    matchId = targetId,
                    confidence = scaledConfidence(best.second.score),
                    matchMethod = best.second.matchMethod,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return target
        }

        return refreshStandardMatchStatus(linkedMatch, platformMatch)
    }

    private fun createStandardMatch(platformMatch: OddsPlatformMatch): OddsMatch {
        val now = System.currentTimeMillis()
        return matchRepository.save(
            OddsMatch(
                sport = "football",
                leagueName = TextEncodingUtils.repairMojibake(platformMatch.rawLeagueName),
                homeTeam = TextEncodingUtils.repairMojibake(platformMatch.rawHomeTeam),
                awayTeam = TextEncodingUtils.repairMojibake(platformMatch.rawAwayTeam),
                startTime = platformMatch.rawStartTime,
                status = oddsMonitorStatusForPlatformMatch(platformMatch, now),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun refreshStandardMatchStatus(match: OddsMatch, platformMatch: OddsPlatformMatch): OddsMatch {
        val nextStatus = oddsMonitorStatusForPlatformMatch(platformMatch)
        val refreshedStatus = if (match.status == "live" && nextStatus == "scheduled") {
            match.status
        } else {
            nextStatus
        }
        val refreshedStartTime = preferredStartTime(match, platformMatch)
        if (match.status == refreshedStatus && match.startTime == refreshedStartTime) {
            return match
        }
        return matchRepository.save(
            match.copy(
                startTime = refreshedStartTime,
                status = refreshedStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun refreshedStartTime(existingStartTime: Long?, incomingStartTime: Long?): Long? {
        if (incomingStartTime == null) {
            return existingStartTime
        }
        if (existingStartTime == null) {
            return incomingStartTime
        }
        val diffMinutes = abs(existingStartTime - incomingStartTime) / 60_000
        return if (diffMinutes > 5) incomingStartTime else existingStartTime
    }

    private fun preferredStartTime(match: OddsMatch, incomingPlatformMatch: OddsPlatformMatch): Long? {
        val matchId = match.id ?: return refreshedStartTime(match.startTime, incomingPlatformMatch.rawStartTime)
        val linkedPlatformMatches = linkedPlatformMatchesForStandardMatch(matchId, incomingPlatformMatch)
        if (linkedPlatformMatches.isEmpty()) {
            return refreshedStartTime(match.startTime, incomingPlatformMatch.rawStartTime)
        }
        val preferred = (linkedPlatformMatches + incomingPlatformMatch)
            .asSequence()
            .filter { it.rawStartTime != null }
            .sortedWith(
                compareBy<OddsPlatformMatch> { startTimeSourcePriority(it.sourceKey) }
                    .thenByDescending { it.updatedAt }
            )
            .firstOrNull()
        return preferred?.rawStartTime ?: refreshedStartTime(match.startTime, incomingPlatformMatch.rawStartTime)
    }

    private fun linkedPlatformMatchesForStandardMatch(
        matchId: Long,
        incomingPlatformMatch: OddsPlatformMatch
    ): List<OddsPlatformMatch> {
        val linkedPlatformIds = linkRepository.findByMatchId(matchId)
            .map { it.platformMatchId }
            .filter { it != incomingPlatformMatch.id }
            .distinct()
        return if (linkedPlatformIds.isEmpty()) {
            emptyList()
        } else {
            platformMatchRepository.findAllById(linkedPlatformIds).toList()
        }
    }

    private fun startTimeSourcePriority(sourceKey: String): Int {
        return when (sourceKey) {
            "crown" -> 0
            "pinnacle" -> 1
            else -> 2
        }
    }

    private fun saveLink(matchId: Long, platformMatchId: Long, confidence: Double, matchMethod: String) {
        if (linkRepository.findByPlatformMatchId(platformMatchId) != null) {
            return
        }
        linkRepository.save(
            OddsMatchLink(
                matchId = matchId,
                platformMatchId = platformMatchId,
                confidence = scaledConfidence(confidence),
                matchMethod = matchMethod,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun scaledConfidence(confidence: Double): BigDecimal {
        return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP)
    }

    private fun OddsPlatformMatch.toCandidate(): OddsMatchCandidate {
        return OddsMatchCandidate(
            id = id,
            leagueName = rawLeagueName,
            homeTeam = rawHomeTeam,
            awayTeam = rawAwayTeam,
            startTime = rawStartTime
        )
    }

    private fun OddsMatch.toCandidate(): OddsMatchCandidate {
        return OddsMatchCandidate(
            id = id,
            leagueName = leagueName,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            startTime = startTime
        )
    }
}
