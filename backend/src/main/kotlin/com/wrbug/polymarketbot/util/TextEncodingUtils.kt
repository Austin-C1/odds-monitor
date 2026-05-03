package com.wrbug.polymarketbot.util

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object TextEncodingUtils {
    private val mojibakeRegex = Regex("[ÃÂâèéåçäæ骞鐨囧啝鑻辫秴瑗挎欐妯璁╃澶]")
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
