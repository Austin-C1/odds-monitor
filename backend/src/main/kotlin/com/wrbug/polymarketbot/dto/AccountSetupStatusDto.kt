package com.wrbug.polymarketbot.dto

data class AccountSetupStatusDto(
    val proxyDeployed: Boolean,
    
    val tradingEnabled: Boolean,
    
    val tokensApproved: Boolean,
    
    val approvalDetails: Map<String, String>? = null,
    
    val error: String? = null
)

data class ExecuteSetupStepRequest(
    val accountId: Long? = null,
    val step: Int? = null
)

data class ExecuteSetupStepResponse(
    val success: Boolean = false,
    val redirectUrl: String? = null,
    val transactionHash: String? = null
)

data class AccountImportResponse(
    val account: AccountDto,
    val setupStatus: AccountSetupStatusDto? = null
)
