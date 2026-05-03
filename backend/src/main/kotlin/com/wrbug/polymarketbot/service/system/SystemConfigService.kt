package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.dto.SystemConfigUpdateRequest
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class SystemConfigService(
    private val systemConfigRepository: SystemConfigRepository,
    private val cryptoUtils: CryptoUtils,
    @Value("\${telegram.order-notification.min-amount-usdc:10}")
    private val defaultOrderNotificationMinAmount: BigDecimal = BigDecimal("10")
) {

    private val logger = LoggerFactory.getLogger(SystemConfigService::class.java)

    companion object {
        const val CONFIG_KEY_BUILDER_API_KEY = "builder.api_key"
        const val CONFIG_KEY_BUILDER_SECRET = "builder.secret"
        const val CONFIG_KEY_BUILDER_PASSPHRASE = "builder.passphrase"
        const val CONFIG_KEY_AUTO_REDEEM = "auto_redeem"
        const val CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT = "telegram.order_notification_min_amount_usdc"
    }

    fun getSystemConfig(): SystemConfigDto {
        val builderApiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val builderSecret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val builderPassphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        val autoRedeem = isAutoRedeemEnabled()
        val orderNotificationMinAmount = getOrderNotificationMinAmount()
        val builderApiKeyDisplay = builderApiKey?.let {
            try {
                cryptoUtils.decrypt(it)
            } catch (e: Exception) {
                null
            }
        }

        val builderSecretDisplay = builderSecret?.let {
            try {
                cryptoUtils.decrypt(it)
            } catch (e: Exception) {
                null
            }
        }

        val builderPassphraseDisplay = builderPassphrase?.let {
            try {
                cryptoUtils.decrypt(it)
            } catch (e: Exception) {
                null
            }
        }

        return SystemConfigDto(
            builderApiKeyConfigured = builderApiKey != null,
            builderSecretConfigured = builderSecret != null,
            builderPassphraseConfigured = builderPassphrase != null,
            builderApiKeyDisplay = builderApiKeyDisplay,
            builderSecretDisplay = builderSecretDisplay,
            builderPassphraseDisplay = builderPassphraseDisplay,
            autoRedeemEnabled = autoRedeem,
            orderNotificationMinAmountUsdc = formatDecimal(orderNotificationMinAmount)
        )
    }

    @Transactional
    fun updateBuilderApiKey(request: SystemConfigUpdateRequest): Result<SystemConfigDto> {
        return try {
            if (request.builderApiKey != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_API_KEY,
                    if (request.builderApiKey.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderApiKey)
                    } else {
                        null
                    }
                )
            }
            if (request.builderSecret != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_SECRET,
                    if (request.builderSecret.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderSecret)
                    } else {
                        null
                    }
                )
            }
            if (request.builderPassphrase != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_PASSPHRASE,
                    if (request.builderPassphrase.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderPassphrase)
                    } else {
                        null
                    }
                )
            }
            if (request.autoRedeem != null) {
                updateConfigValue(
                    CONFIG_KEY_AUTO_REDEEM,
                    request.autoRedeem.toString()
                )
            }

            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新系统配置失败", e)
            Result.failure(e)
        }
    }

    fun getBuilderApiKey(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_API_KEY)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderSecret(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_SECRET)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderPassphrase(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)?.let { cryptoUtils.decrypt(it) }
    }

    fun isBuilderApiKeyConfigured(): Boolean {
        val apiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val secret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val passphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        return apiKey != null && secret != null && passphrase != null
    }

    fun isAutoRedeemEnabled(): Boolean {
        val autoRedeemValue = getConfigValue(CONFIG_KEY_AUTO_REDEEM)
        return when (autoRedeemValue?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> false
        }
    }

    fun getOrderNotificationMinAmount(): BigDecimal {
        val savedValue = getConfigValue(CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT)
        val amount = savedValue
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toBigDecimalOrNull()
            ?: defaultOrderNotificationMinAmount

        return if (amount < BigDecimal.ZERO) defaultOrderNotificationMinAmount else amount
    }

    @Transactional
    fun updateAutoRedeem(enabled: Boolean): Result<SystemConfigDto> {
        return try {
            updateConfigValue(CONFIG_KEY_AUTO_REDEEM, enabled.toString())
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新自动赎回配置失败", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun updateOrderNotificationMinAmount(minAmount: BigDecimal): Result<SystemConfigDto> {
        return try {
            require(minAmount >= BigDecimal.ZERO) { "minAmountUsdc must be greater than or equal to 0" }
            updateConfigValue(CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT, formatDecimal(minAmount))
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("Failed to update Telegram order notification minimum amount", e)
            Result.failure(e)
        }
    }

    private fun getConfigValue(configKey: String): String? {
        return systemConfigRepository.findByConfigKey(configKey)?.configValue
    }

    private fun formatDecimal(value: BigDecimal): String {
        return value.stripTrailingZeros().toPlainString()
    }

    private fun updateConfigValue(configKey: String, configValue: String?) {
        val existing = systemConfigRepository.findByConfigKey(configKey)
        if (existing != null) {
            val updated = existing.copy(
                configValue = configValue,
                updatedAt = System.currentTimeMillis()
            )
            systemConfigRepository.save(updated)
        } else {
            val newConfig = SystemConfig(
                configKey = configKey,
                configValue = configValue,
                description = when (configKey) {
                    CONFIG_KEY_BUILDER_API_KEY -> "Builder API Key（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_SECRET -> "Builder Secret（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_PASSPHRASE -> "Builder Passphrase（用于 Gasless 交易）"
                    CONFIG_KEY_AUTO_REDEEM -> "自动赎回（系统级别配置，默认开启）"
                    else -> null
                }
            )
            systemConfigRepository.save(newConfig)
        }
    }
}

