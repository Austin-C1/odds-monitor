package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.service.system.GitHubUpdateService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/update")
class UpdateController(
    private val gitHubUpdateService: GitHubUpdateService
) {

    @GetMapping("/version")
    fun getVersion(): ResponseEntity<ApiResponse<VersionResponse>> {
        return ResponseEntity.ok(
            ApiResponse.success(
                VersionResponse(version = currentVersion())
            )
        )
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<ApiResponse<UpdateStatusResponse>> {
        return ResponseEntity.ok(
            ApiResponse.success(gitHubUpdateService.getStatus())
        )
    }

    @GetMapping("/check")
    fun checkUpdate(): ResponseEntity<ApiResponse<UpdateCheckResponse>> {
        return ResponseEntity.ok(
            ApiResponse.success(gitHubUpdateService.checkUpdate(currentVersion()))
        )
    }

    @PostMapping("/update")
    fun executeUpdate(): ResponseEntity<ApiResponse<Unit>> {
        val result = gitHubUpdateService.startUpdate(currentVersion())
        return result.fold(
            onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
            onFailure = { ResponseEntity.ok(ApiResponse.error(409, it.message ?: "更新启动失败")) }
        )
    }

    private fun currentVersion(): String {
        return javaClass.`package`.implementationVersion ?: "1.0.0"
    }
}

data class VersionResponse(
    val version: String
)

data class UpdateStatusResponse(
    val updating: Boolean,
    val progress: Int,
    val message: String,
    val error: String?
)

data class UpdateCheckResponse(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val latestTag: String,
    val releaseNotes: String,
    val publishedAt: String,
    val prerelease: Boolean
)
