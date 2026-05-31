package com.wrbug.polymarketbot.controller.autobetting

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.AdsPowerBrowserSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionMatchRequest
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionRequest
import com.wrbug.polymarketbot.dto.AdsPowerStartProfileRequest
import com.wrbug.polymarketbot.dto.AdsPowerStatusDto
import com.wrbug.polymarketbot.dto.AutoBettingDecisionDto
import com.wrbug.polymarketbot.dto.AutoBettingQueuedCrownExecutionRequest
import com.wrbug.polymarketbot.dto.AutoBettingSignalRequest
import com.wrbug.polymarketbot.service.autobetting.AdsPowerLocalApiService
import com.wrbug.polymarketbot.service.autobetting.AutoBettingDecisionService
import com.wrbug.polymarketbot.service.autobetting.AutoBettingExecutionService
import com.wrbug.polymarketbot.service.autobetting.AutoBettingQueueService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auto-betting")
class AutoBettingController(
    private val decisionService: AutoBettingDecisionService,
    private val executionService: AutoBettingExecutionService,
    private val queueService: AutoBettingQueueService,
    private val adsPowerLocalApiService: AdsPowerLocalApiService
) {
    @PostMapping("/signals/odds-monitor")
    fun createOddsMonitorSignal(
        @RequestBody request: AutoBettingSignalRequest
    ): ResponseEntity<ApiResponse<AutoBettingDecisionDto>> {
        return ResponseEntity.ok(ApiResponse.success(decisionService.createIntent(request)))
    }

    @PostMapping("/signals/odds-monitor/execute-crown-queue")
    fun executeOddsMonitorCrownQueue(
        @RequestBody request: AutoBettingQueuedCrownExecutionRequest
    ): ResponseEntity<ApiResponse<List<AutoBettingDecisionDto>>> {
        return ResponseEntity.ok(ApiResponse.success(queueService.executeQueuedCrownSignal(request)))
    }

    @PostMapping("/intents/recent")
    fun listRecentIntents(): ResponseEntity<ApiResponse<List<AutoBettingDecisionDto>>> {
        return ResponseEntity.ok(ApiResponse.success(decisionService.listRecentIntents()))
    }

    @PostMapping("/intents/verified-placed")
    fun listVerifiedPlacedIntents(): ResponseEntity<ApiResponse<List<AutoBettingDecisionDto>>> {
        return ResponseEntity.ok(ApiResponse.success(decisionService.listRecentVerifiedPlacedIntents()))
    }

    @PostMapping("/adspower/status")
    fun checkAdsPowerStatus(): ResponseEntity<ApiResponse<AdsPowerStatusDto>> {
        return ResponseEntity.ok(ApiResponse.success(adsPowerLocalApiService.checkStatus()))
    }

    @PostMapping("/adspower/start-profile")
    fun startAdsPowerProfile(
        @RequestBody request: AdsPowerStartProfileRequest
    ): ResponseEntity<ApiResponse<AdsPowerBrowserSessionDto>> {
        return ResponseEntity.ok(ApiResponse.success(adsPowerLocalApiService.startProfile(request.profileId)))
    }

    @PostMapping("/adspower/crown-session")
    fun checkAdsPowerCrownSession(
        @RequestBody request: AdsPowerCrownSessionRequest
    ): ResponseEntity<ApiResponse<AdsPowerCrownSessionDto>> {
        return ResponseEntity.ok(ApiResponse.success(
            adsPowerLocalApiService.checkCrownSession(request.profileId, request.loginUrl, request.loginName)
        ))
    }

    @PostMapping("/adspower/crown-session/match")
    fun matchAdsPowerCrownSession(
        @RequestBody request: AdsPowerCrownSessionMatchRequest
    ): ResponseEntity<ApiResponse<AdsPowerCrownSessionDto>> {
        return ResponseEntity.ok(ApiResponse.success(
            adsPowerLocalApiService.matchCrownSession(request.loginName, request.loginUrl, request.preferredProfileId)
        ))
    }
}
