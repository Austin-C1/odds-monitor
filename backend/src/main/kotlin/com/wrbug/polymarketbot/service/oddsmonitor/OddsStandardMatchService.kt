package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class OddsStandardMatchService(
    private val matchRepository: OddsMatchRepository,
    private val linkRepository: OddsMatchLinkRepository
) {
    @Transactional
    fun resolveStandardMatch(platformMatch: OddsPlatformMatch): OddsMatch {
        val platformMatchId = platformMatch.id ?: return createStandardMatch(platformMatch)
        val existingLink = linkRepository.findByPlatformMatchId(platformMatchId)
        if (existingLink != null) {
            matchRepository.findById(existingLink.matchId).orElse(null)?.let {
                return refreshStandardMatchStatus(it, platformMatch)
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
        if (match.status == nextStatus || (match.status == "live" && nextStatus == "scheduled")) {
            return match
        }
        return matchRepository.save(
            match.copy(
                status = nextStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun saveLink(matchId: Long, platformMatchId: Long, confidence: Double, matchMethod: String) {
        if (linkRepository.findByPlatformMatchId(platformMatchId) != null) {
            return
        }
        linkRepository.save(
            OddsMatchLink(
                matchId = matchId,
                platformMatchId = platformMatchId,
                confidence = BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                matchMethod = matchMethod,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
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
