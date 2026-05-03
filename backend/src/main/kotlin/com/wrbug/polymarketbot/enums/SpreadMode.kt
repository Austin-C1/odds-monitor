package com.wrbug.polymarketbot.enums

enum class SpreadMode(val value: Int, val description: String) {
    NONE(0, "无"),
    
    FIXED(1, "固定"),
    
    AUTO(2, "自动");
    
    companion object {
        fun fromValue(value: Int?): SpreadMode {
            if (value == null) {
                return NONE
            }
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("未知的价差模式: $value")
        }
        
        fun fromValueOrDefault(value: Int?, default: SpreadMode = NONE): SpreadMode {
            if (value == null) {
                return default
            }
            return values().find { it.value == value } ?: default
        }
        
        fun fromString(value: String?): SpreadMode {
            if (value.isNullOrBlank()) {
                return NONE
            }
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的价差模式: $value")
        }
    }
}
