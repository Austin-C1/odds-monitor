package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.jsoup.Jsoup
import org.springframework.stereotype.Component

@Component
class PinnaclePageReadiness {
    fun hasLoadedOdds(html: String): Boolean {
        if (html.isBlank()) {
            return false
        }
        val document = Jsoup.parse(html)
        val oddspage = document.selectFirst("#oddspage") ?: return false
        if (oddspage.classNames().any { it == "is-loading" }) {
            return false
        }
        val text = oddspage.text()
        val hasTeams = Regex("[\\p{IsHan}A-Za-z]{2,}").findAll(text).count() >= 3
        val hasOddsNumbers = Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(text).count() >= 4
        return hasTeams && hasOddsNumbers
    }
}
