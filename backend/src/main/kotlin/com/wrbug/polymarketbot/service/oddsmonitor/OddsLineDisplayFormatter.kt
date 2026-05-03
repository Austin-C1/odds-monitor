package com.wrbug.polymarketbot.service.oddsmonitor

import java.math.BigDecimal

object OddsLineDisplayFormatter {
    private val slashSeparator = Regex("""\s*/\s*""")
    private val positiveDashRange = Regex("""^(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)$""")

    fun format(marketType: String, lineValue: String?): String? {
        marketType.takeIf { it.isNotBlank() } ?: return lineValue?.trim()?.takeIf { it.isNotBlank() }
        val value = lineValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val slashParts = value.split(slashSeparator).filter { it.isNotBlank() }
        if (slashParts.size > 1) {
            return slashParts.joinToString("/") { it.formatNumberToken() }
        }

        positiveDashRange.matchEntire(value.replace(" ", ""))?.let { match ->
            return listOf(match.groupValues[1], match.groupValues[2])
                .joinToString("/") { it.formatNumberToken() }
        }

        return value.formatNumberToken()
    }

    private fun String.formatNumberToken(): String {
        val value = trim()
        return runCatching { BigDecimal(value).stripTrailingZeros().toPlainString() }.getOrDefault(value)
    }
}
