package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.springframework.stereotype.Component

@Component
class PinnaclePageGuard {
    fun ensureUsableFootballHtml(html: String) {
        if (html.isBlank()) {
            throw PinnacleCollectionException("failed_empty", "pinnacle football page html is empty")
        }

        val lower = html.lowercase()
        if (
            lower.contains("attention required! | cloudflare") ||
            lower.contains("cf-error-details") ||
            lower.contains("you have been blocked")
        ) {
            throw PinnacleCollectionException("failed_network", "pinnacle access was blocked by Cloudflare")
        }
    }
}
