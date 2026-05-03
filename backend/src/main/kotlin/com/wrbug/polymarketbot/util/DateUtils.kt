package com.wrbug.polymarketbot.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object DateUtils {
    
    private val isoFormatter = DateTimeFormatter.ISO_DATE_TIME
    
    private val displayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    
    fun parseToTimestamp(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) {
            return null
        }
        
        return try {
            val instant = Instant.parse(dateString)
            instant.toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                val dateTime = java.time.ZonedDateTime.parse(dateString, isoFormatter)
                dateTime.toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    fun formatFromTimestamp(timestamp: Long?): String? {
        if (timestamp == null) {
            return null
        }
        
        return try {
            val instant = Instant.ofEpochMilli(timestamp)
            instant.toString()
        } catch (e: Exception) {
            null
        }
    }
    
    fun formatDateTime(timestamp: Long? = null): String {
        val instant = if (timestamp != null) {
            Instant.ofEpochMilli(timestamp)
        } else {
            Instant.now()
        }
        return displayFormatter.format(instant)
    }
    
    fun formatDuration(milliseconds: Long): String {
        if (milliseconds < 0) {
            return "0分钟"
        }
        
        val totalSeconds = milliseconds / 1000
        val days = totalSeconds / (24 * 60 * 60)
        val hours = (totalSeconds % (24 * 60 * 60)) / (60 * 60)
        val minutes = (totalSeconds % (60 * 60)) / 60
        
        val parts = mutableListOf<String>()
        
        if (days > 0) {
            parts.add("${days}天")
        }
        if (hours > 0) {
            parts.add("${hours}小时")
        }
        if (minutes > 0 || parts.isEmpty()) {
            parts.add("${minutes}分钟")
        }
        
        return parts.joinToString("")
    }
}

