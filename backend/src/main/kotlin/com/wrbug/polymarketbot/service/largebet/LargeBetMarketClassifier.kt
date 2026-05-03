package com.wrbug.polymarketbot.service.largebet

import org.springframework.stereotype.Service

@Service
class LargeBetMarketClassifier {

    fun classify(title: String?, category: String? = null, tags: List<String> = emptyList()): String? {
        val text = buildString {
            append(title.orEmpty())
            append(' ')
            append(category.orEmpty())
            append(' ')
            append(tags.joinToString(" "))
        }.lowercase()

        return when {
            listOf("soccer", "football", "uefa", "premier league", "la liga", "serie a", "bundesliga").any { text.contains(it) } -> "FOOTBALL"
            listOf("basketball", "nba", "ncaab", "wnba", "euroleague").any { text.contains(it) } -> "BASKETBALL"
            else -> null
        }
    }
}
