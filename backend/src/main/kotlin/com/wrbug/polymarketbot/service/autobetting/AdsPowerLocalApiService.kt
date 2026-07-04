package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.AdsPowerBrowserSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerProfileActiveDto
import com.wrbug.polymarketbot.dto.AdsPowerStatusDto
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerCdpClient
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerLocalApiClient
import com.wrbug.polymarketbot.service.autobetting.adspower.DEFAULT_ADSPOWER_BASE_URL
import com.wrbug.polymarketbot.service.autobetting.crown.CrownBetHistoryVerifier
import com.wrbug.polymarketbot.service.autobetting.crown.CrownBetPlacementService
import com.wrbug.polymarketbot.service.autobetting.crown.CrownSessionMatcher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AdsPowerLocalApiService(
    objectMapper: ObjectMapper,
    @Value("\${adspower.local-api-url:\${adspower.local.api.url:http://127.0.0.1:50325}}")
    baseUrl: String = DEFAULT_ADSPOWER_BASE_URL,
    @Value("\${adspower.api-key:\${adspower.api.key:}}")
    apiKey: String? = null
) : CrownBetPlacementGateway {
    private val apiClient = AdsPowerLocalApiClient(objectMapper, baseUrl, apiKey)
    private val cdpClient = AdsPowerCdpClient(objectMapper)
    private val historyVerifier = CrownBetHistoryVerifier(apiClient, cdpClient, objectMapper)
    private val placementService = CrownBetPlacementService(apiClient, cdpClient, historyVerifier, objectMapper)
    private val sessionMatcher = CrownSessionMatcher(apiClient, cdpClient)

    fun checkStatus(now: Long = System.currentTimeMillis()): AdsPowerStatusDto {
        return apiClient.checkStatus(now)
    }

    fun startProfile(profileId: String, now: Long = System.currentTimeMillis()): AdsPowerBrowserSessionDto {
        return apiClient.startProfile(profileId, now)
    }

    fun checkProfileActive(profileId: String, now: Long = System.currentTimeMillis()): AdsPowerProfileActiveDto {
        return apiClient.checkProfileActive(profileId, now)
    }

    fun checkCrownSession(
        profileId: String,
        loginUrl: String? = null,
        loginName: String? = null,
        now: Long = System.currentTimeMillis()
    ): AdsPowerCrownSessionDto {
        return sessionMatcher.checkCrownSession(profileId, loginUrl, loginName, now)
    }

    fun matchCrownSession(
        loginName: String,
        loginUrl: String? = null,
        preferredProfileId: String? = null,
        now: Long = System.currentTimeMillis()
    ): AdsPowerCrownSessionDto {
        return sessionMatcher.matchCrownSession(loginName, loginUrl, preferredProfileId, now)
    }

    override fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult {
        return placementService.placeBet(command)
    }

    override fun verifyPlacedBet(command: CrownBetPlacementCommand, ticketReference: String?): CrownBetPlacementResult {
        return historyVerifier.verifyPlacedBet(command, ticketReference)
    }
}
