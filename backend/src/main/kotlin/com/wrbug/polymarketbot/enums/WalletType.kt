package com.wrbug.polymarketbot.enums

enum class WalletType(val value: String, val description: String) {
    MAGIC("magic", "Magic（邮箱/OAuth登录）"),
    
    SAFE("safe", "Safe（Web3钱包）");
    
    companion object {
        fun fromString(value: String?): WalletType {
            if (value.isNullOrBlank()) {
                return SAFE
            }
            return values().find { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的钱包类型: $value")
        }
        
        fun fromStringOrDefault(value: String?, default: WalletType = SAFE): WalletType {
            if (value.isNullOrBlank()) {
                return default
            }
            return values().find { it.value.equals(value, ignoreCase = true) } ?: default
        }
        
        fun isValid(value: String?): Boolean {
            if (value.isNullOrBlank()) {
                return false
            }
            return values().any { it.value.equals(value, ignoreCase = true) }
        }
    }
}
