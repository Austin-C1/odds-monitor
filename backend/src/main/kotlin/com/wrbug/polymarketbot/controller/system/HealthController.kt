package com.wrbug.polymarketbot.controller.system

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/api/system/health")
class HealthController {
    
    @GetMapping
    fun health(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "status" to "UP",
            "timestamp" to System.currentTimeMillis(),
            "service" to "OddsMonitor-v1-backend"
        ))
    }
}



