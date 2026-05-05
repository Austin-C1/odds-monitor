package com.wrbug.polymarketbot.controller.oddsmonitor

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.OddsAlertRecordDto
import com.wrbug.polymarketbot.dto.OddsCollectionLogDto
import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
import com.wrbug.polymarketbot.dto.OddsDataSourceStatusDto
import com.wrbug.polymarketbot.dto.ListOddsLeagueFilterRequest
import com.wrbug.polymarketbot.dto.OddsMonitorDashboardDto
import com.wrbug.polymarketbot.dto.OddsLeagueFilterDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDetailDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDetailRequest
import com.wrbug.polymarketbot.dto.SaveOddsLeagueFilterRequest
import com.wrbug.polymarketbot.dto.SaveOddsDataSourceConfigsRequest
import com.wrbug.polymarketbot.service.oddsmonitor.OddsMonitorService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/odds-monitor")
class OddsMonitorController(
    private val oddsMonitorService: OddsMonitorService
) {
    @PostMapping("/dashboard")
    fun dashboard(): ResponseEntity<ApiResponse<OddsMonitorDashboardDto>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.getDashboard()))
    }

    @PostMapping("/match-detail")
    fun matchDetail(
        @RequestBody request: OddsMonitorMatchDetailRequest
    ): ResponseEntity<ApiResponse<OddsMonitorMatchDetailDto?>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.getMatchDetail(request.matchId)))
    }

    @PostMapping("/data-sources/configs/list")
    fun listDataSourceConfigs(): ResponseEntity<ApiResponse<List<OddsDataSourceConfigDto>>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.listDataSourceConfigs()))
    }

    @PostMapping("/data-sources/configs/save")
    fun saveDataSourceConfigs(
        @RequestBody request: SaveOddsDataSourceConfigsRequest
    ): ResponseEntity<ApiResponse<List<OddsDataSourceConfigDto>>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.saveDataSourceConfigs(request.configs)))
    }

    @PostMapping("/leagues/list")
    fun listLeagues(
        @RequestBody(required = false) request: ListOddsLeagueFilterRequest?
    ): ResponseEntity<ApiResponse<OddsLeagueFilterDto>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.listLeagueFilter(request?.sourceKey)))
    }

    @PostMapping("/leagues/save")
    fun saveLeagues(
        @RequestBody request: SaveOddsLeagueFilterRequest
    ): ResponseEntity<ApiResponse<OddsLeagueFilterDto>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.saveLeagueFilter(request.selectedLeagues, request.sourceKey)))
    }

    @PostMapping("/data-sources/status/list")
    fun listDataSourceStatuses(): ResponseEntity<ApiResponse<List<OddsDataSourceStatusDto>>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.listDataSourceStatuses()))
    }

    @PostMapping("/alerts/list")
    fun listAlertRecords(): ResponseEntity<ApiResponse<List<OddsAlertRecordDto>>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.listAlertRecords()))
    }

    @PostMapping("/logs/list")
    fun listCollectionLogs(): ResponseEntity<ApiResponse<List<OddsCollectionLogDto>>> {
        return ResponseEntity.ok(ApiResponse.success(oddsMonitorService.listCollectionLogs()))
    }
}
