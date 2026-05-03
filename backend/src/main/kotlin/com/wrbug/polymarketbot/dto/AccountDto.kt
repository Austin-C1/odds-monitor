package com.wrbug.polymarketbot.dto

data class AccountImportRequest(
    val privateKey: String,
    val walletAddress: String,
    val accountName: String? = null,
    val isEnabled: Boolean = true,
    val walletType: String = "magic"
)

data class CheckProxyOptionsRequest(
    val walletAddress: String,
    val privateKey: String? = null,
    val mnemonic: String? = null
)

data class ProxyOptionDto(
    val walletType: String,
    val proxyAddress: String,
    val descriptionKey: String,
    val availableBalance: String,
    val positionBalance: String,
    val totalBalance: String,
    val positionCount: Int,
    val hasAssets: Boolean,
    val error: String? = null
)

data class CheckProxyOptionsResponse(
    val options: List<ProxyOptionDto>
)

data class AccountUpdateRequest(
    val accountId: Long,
    val accountName: String? = null,
    val remark: String? = null,
    val isEnabled: Boolean? = null,
    val builderApiKey: String? = null,
    val builderSecret: String? = null,
    val builderPassphrase: String? = null,
    val autoRedeemEnabled: Boolean? = null
)

data class SystemConfigUpdateRequest(
    val builderApiKey: String? = null,
    val builderSecret: String? = null,
    val builderPassphrase: String? = null,
    val autoRedeem: Boolean? = null
)

data class OrderNotificationMinAmountUpdateRequest(
    val minAmountUsdc: String? = null
)

data class SystemConfigDto(
    val builderApiKeyConfigured: Boolean,
    val builderSecretConfigured: Boolean,
    val builderPassphraseConfigured: Boolean,
    val builderApiKeyDisplay: String? = null,
    val builderSecretDisplay: String? = null,
    val builderPassphraseDisplay: String? = null,
    val autoRedeemEnabled: Boolean = true,
    val orderNotificationMinAmountUsdc: String = "10"
)

data class AccountDeleteRequest(
    val accountId: Long
)

data class AccountDetailRequest(
    val accountId: Long? = null
)

data class AccountBalanceRequest(
    val accountId: Long? = null
)

data class AccountDto(
    val id: Long,
    val walletAddress: String,
    val proxyAddress: String,
    val accountName: String?,
    val remark: String? = null,
    val isEnabled: Boolean,
    val walletType: String = "magic",
    val apiKeyConfigured: Boolean,
    val apiSecretConfigured: Boolean,
    val apiPassphraseConfigured: Boolean,
    val builderConfigured: Boolean,
    val builderApiKeyDisplay: String? = null,
    val builderSecretDisplay: String? = null,
    val builderPassphraseDisplay: String? = null,
    val autoRedeemEnabled: Boolean = true,
    val balance: String? = null,
    val totalOrders: Long? = null,
    val totalPnl: String? = null,
    val activeOrders: Long? = null,
    val completedOrders: Long? = null,
    val positionCount: Long? = null
)

data class AccountListResponse(
    val list: List<AccountDto>,
    val total: Long
)

data class WalletBalanceResponse(
    val availableBalance: String,
    val positionBalance: String,
    val totalBalance: String,
    val positions: List<PositionDto> = emptyList()
)

data class AccountBalanceResponse(
    val availableBalance: String,
    val positionBalance: String,
    val totalBalance: String,
    val positions: List<PositionDto> = emptyList()
)

data class PositionDto(
    val marketId: String,
    val title: String?,
    val side: String,
    val quantity: String,
    val avgPrice: String,
    val currentValue: String,
    val pnl: String? = null
)

data class AccountPositionDto(
    val accountId: Long,
    val accountName: String?,
    val walletAddress: String,
    val proxyAddress: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val eventSlug: String? = null,
    val marketIcon: String?,
    val side: String,
    val outcomeIndex: Int? = null,
    val quantity: String,
    val originalQuantity: String? = null,
    val avgPrice: String,
    val currentPrice: String,
    val currentValue: String,
    val initialValue: String,
    val pnl: String,
    val percentPnl: String,
    val realizedPnl: String?,
    val percentRealizedPnl: String?,
    val redeemable: Boolean,
    val mergeable: Boolean,
    val endDate: String?,
    val isCurrent: Boolean = true
)

data class PositionListResponse(
    val currentPositions: List<AccountPositionDto>,
    val historyPositions: List<AccountPositionDto>
)

data class PositionSellRequest(
    val accountId: Long,
    val marketId: String,
    val side: String,
    val outcomeIndex: Int? = null,
    val orderType: String,
    val quantity: String? = null,
    val percent: String? = null,
    val price: String? = null
)

data class PositionSellResponse(
    val orderId: String,
    val marketId: String,
    val side: String,
    val orderType: String,
    val quantity: String,
    val price: String?,
    val status: String,
    val createdAt: Long
)

data class MarketPriceRequest(
    val marketId: String,
    val outcomeIndex: Int? = null
)

data class LatestPriceRequest(
    val tokenId: String
)

data class MarketPriceResponse(
    val marketId: String,
    val currentPrice: String
)

data class PositionRedeemRequest(
    val positions: List<AccountRedeemPositionItem>
)

data class AccountRedeemPositionItem(
    val accountId: Long,
    val marketId: String,
    val outcomeIndex: Int,
    val side: String? = null
)

data class RedeemPositionItem(
    val marketId: String,
    val outcomeIndex: Int,
    val side: String? = null
)

data class PositionRedeemResponse(
    val transactions: List<AccountRedeemTransaction>,
    val totalRedeemedValue: String,
    val createdAt: Long
)

data class AccountRedeemTransaction(
    val accountId: Long,
    val accountName: String?,
    val transactionHash: String,
    val positions: List<RedeemedPositionInfo>
)

data class RedeemedPositionInfo(
    val marketId: String,
    val side: String,
    val outcomeIndex: Int,
    val quantity: String,
    val value: String
)

data class RedeemablePositionsSummary(
    val totalCount: Int,
    val totalValue: String,
    val positions: List<RedeemablePositionInfo>
)

data class RedeemablePositionInfo(
    val accountId: Long,
    val accountName: String?,
    val marketId: String,
    val marketTitle: String?,
    val side: String,
    val outcomeIndex: Int,
    val quantity: String,
    val value: String
)


