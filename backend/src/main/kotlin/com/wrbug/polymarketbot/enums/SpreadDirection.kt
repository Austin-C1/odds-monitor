package com.wrbug.polymarketbot.enums

enum class SpreadDirection(val value: Int, val description: String) {
    MIN(0, "最小价差"),
    
    MAX(1, "最大价差");
    
    companion object {
        fun fromValue(value: Int?): SpreadDirection {
            if (value == null) {
                return MIN
            }
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("未知的价差方向: $value")
        }
        
        fun fromValueOrDefault(value: Int?, default: SpreadDirection = MIN): SpreadDirection {
            if (value == null) {
                return default
            }
            return values().find { it.value == value } ?: default
        }
        
        fun fromString(value: String?): SpreadDirection {
            if (value.isNullOrBlank()) {
                return MIN
            }
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的价差方向: $value")
        }
    }
}
