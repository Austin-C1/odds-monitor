package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.api.MarketResponse

fun MarketResponse?.getEventSlug(): String? {
    return this?.events?.firstOrNull()?.slug ?: this?.slug
}

fun MarketResponse?.getDisplaySlug(): String? {
    return this?.slug
}

