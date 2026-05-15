package com.wrbug.polymarketbot.util

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object TextEncodingUtils {
    private val mojibakeCodePoints = intArrayOf(
        0x00C3, 0x00C2, 0x00E2, 0x00E8, 0x00E9, 0x00E5, 0x00E7, 0x00E4, 0x00E6,
        0x9A9E, 0x9428, 0x56E7, 0x555D, 0x947B, 0x8FAB, 0x79F4, 0x7457, 0x630E,
        0x6B10, 0x59AF, 0x7481, 0x2543, 0x6FB6, 0x9354, 0x3126, 0x95AB, 0x6C33,
        0x7E43, 0x935A, 0x581F, 0x74A7, 0x65C2, 0x5DFC, 0x9359, 0x6A3A, 0x59E9,
        0x9429, 0x5F5B
    )
    private val mojibakeRegex = Regex(
        mojibakeCodePoints.joinToString(prefix = "[", postfix = "]") { Regex.escape(it.toChar().toString()) }
    )
    private val invisibleMarksRegex = Regex("[\\u200E\\u200F\\u202A-\\u202E]")
    private val gbk = Charset.forName("GBK")

    fun repairMojibake(value: String?): String {
        if (value.isNullOrBlank()) {
            return value.orEmpty()
        }

        val cleaned = value.replace(invisibleMarksRegex, "").trim()
        if (!mojibakeRegex.containsMatchIn(cleaned)) {
            return cleaned
        }

        return try {
            val candidates = listOf(
                runCatching { String(cleaned.toByteArray(gbk), StandardCharsets.UTF_8) }.getOrNull(),
                runCatching { String(cleaned.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8) }.getOrNull()
            ).mapNotNull { it?.replace(invisibleMarksRegex, "")?.trim() }

            candidates.minByOrNull { mojibakeScore(it) }?.takeIf {
                mojibakeScore(it) < mojibakeScore(cleaned)
            } ?: cleaned
        } catch (_: CharacterCodingException) {
            cleaned
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun mojibakeScore(value: String): Int {
        return mojibakeRegex.findAll(value).count() + value.count { it == '\uFFFD' || it == '?' }
    }
}
