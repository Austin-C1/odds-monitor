package com.wrbug.polymarketbot.service.oddsmonitor

import java.math.BigDecimal
import java.math.RoundingMode

object OddsLineDisplayFormatter {
    private val slashSeparator = Regex("""\s*/\s*""")
    private val positiveDashRange = Regex("""^(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)$""")
    private val half = BigDecimal("0.5")
    private val four = BigDecimal("4")

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

        return value.toBigDecimalOrNull()?.formatQuarterSplit() ?: value.formatNumberToken()
    }

    private fun String.formatNumberToken(): String {
        val value = trim()
        return runCatching { BigDecimal(value).stripTrailingZeros().toPlainString() }.getOrDefault(value)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? {
        return runCatching { BigDecimal(trim()) }.getOrNull()
    }

    private fun BigDecimal.formatQuarterSplit(): String? {
        val quarterUnits = multiply(four).stripTrailingZeros()
        if (quarterUnits.scale() > 0) {
            return null
        }
        if (quarterUnits.toBigIntegerExact().remainder(BigDecimal("2").toBigInteger()).signum() == 0) {
            return null
        }
        val firstAbs = abs()
            .divide(half, 0, RoundingMode.DOWN)
            .multiply(half)
        val secondAbs = firstAbs.add(half)
        val first = if (signum() < 0) firstAbs.negate() else firstAbs
        val second = if (signum() < 0) secondAbs.negate() else secondAbs
        return "${first.formatPlain()}/${second.formatPlain()}"
    }

    private fun BigDecimal.formatPlain(): String {
        val normalized = stripTrailingZeros()
        return if (normalized.compareTo(BigDecimal.ZERO) == 0) {
            "0"
        } else {
            normalized.toPlainString()
        }
    }
}
