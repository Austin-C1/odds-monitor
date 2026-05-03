package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.eq
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.getEventSlug
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.event.AccountStateChangedEvent
import com.wrbug.polymarketbot.util.CryptoUtils
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.BigInteger

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService,
    private val apiKeyService: PolymarketApiKeyService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val marketService: MarketService,
    private val telegramNotificationService: TelegramNotificationService? = null,
    private val relayClientService: RelayClientService,
    private val jsonUtils: JsonUtils,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(AccountService::class.java)
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val BUY_PRICE_ADJUSTMENT = BigDecimal("0.01")
    private val SELL_PRICE_ADJUSTMENT = BigDecimal("0.02")

    @Transactional
    suspend fun importAccount(request: AccountImportRequest): Result<AccountDto> {
        return try {
            if (!isValidWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }
            if (!isValidPrivateKey(request.privateKey)) {
                return Result.failure(IllegalArgumentException("无效的私钥格式"))
            }
            val apiKeyCreds = runBlocking {
                val result = apiKeyService.createOrDeriveApiKey(
                    privateKey = request.privateKey,
                    walletAddress = request.walletAddress,
                    chainId = 137L
                )

                if (result.isSuccess) {
                    val creds = result.getOrNull()
                    if (creds != null) {
                        creds
                    } else {
                        logger.error("自动获取 API Key 返回空值")
                        throw IllegalStateException("自动获取 API Key 失败：返回值为空")
                    }
                } else {
                    val error = result.exceptionOrNull()
                    logger.error("自动获取 API Key 失败: ${error?.message}")
                    throw IllegalStateException("自动获取 API Key 失败: ${error?.message}。请确保私钥有效且账户已激活")
                }
            }
            val proxyAddress = runBlocking {
                val walletTypeEnum = WalletType.fromStringOrDefault(request.walletType, WalletType.MAGIC)
                val proxyResult = blockchainService.getProxyAddress(request.walletAddress, walletTypeEnum)
                if (proxyResult.isSuccess) {
                    val address = proxyResult.getOrNull()
                    if (address != null) {
                        address
                    } else {
                        logger.error("获取代理地址返回空值")
                        throw IllegalStateException("获取代理地址失败：返回值为空")
                    }
                } else {
                    val error = proxyResult.exceptionOrNull()
                    logger.error("获取代理地址失败: ${error?.message}")
                    throw IllegalStateException("获取代理地址失败: ${error?.message}。请确保已配置 Ethereum RPC URL 且 RPC 节点可用")
                }
            }
            if (accountRepository.existsByProxyAddress(proxyAddress)) {
                return Result.failure(IllegalArgumentException("ACCOUNT_ALREADY_EXISTS"))
            }
            val encryptedPrivateKey = cryptoUtils.encrypt(request.privateKey)
            val encryptedApiSecret = apiKeyCreds.secret.let { cryptoUtils.encrypt(it) }
            val encryptedApiPassphrase = apiKeyCreds.passphrase.let { cryptoUtils.encrypt(it) }
            val accountName = if (request.accountName.isNullOrBlank()) {
                val walletTypeEnum = WalletType.fromStringOrDefault(request.walletType, WalletType.MAGIC)
                val typeLabel = walletTypeEnum.name.uppercase()
                val proxyWithoutPrefix = if (proxyAddress.startsWith("0x") || proxyAddress.startsWith("0X")) {
                    proxyAddress.substring(2)
                } else {
                    proxyAddress
                }
                val suffix = if (proxyWithoutPrefix.length >= 4) {
                    proxyWithoutPrefix.substring(proxyWithoutPrefix.length - 4).uppercase()
                } else {
                    proxyWithoutPrefix.uppercase()
                }
                "$typeLabel-$suffix"
            } else {
                request.accountName.trim()
            }
            val account = Account(
                privateKey = encryptedPrivateKey,
                walletAddress = request.walletAddress,
                proxyAddress = proxyAddress,
                apiKey = apiKeyCreds.apiKey,
                apiSecret = encryptedApiSecret,
                apiPassphrase = encryptedApiPassphrase,
                accountName = accountName,
                isDefault = false,
                isEnabled = request.isEnabled,
                walletType = request.walletType,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val saved = accountRepository.save(account)
            publishAccountStateChanged(saved)

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("导入账户失败", e)
            Result.failure(e)
        }
    }

    suspend fun checkProxyOptions(request: CheckProxyOptionsRequest): Result<CheckProxyOptionsResponse> {
        return try {
            if (!isValidWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }
            if (request.privateKey.isNullOrBlank() && request.mnemonic.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("必须提供私钥或助记词"))
            }

            val options = mutableListOf<ProxyOptionDto>()
            val isPrivateKeyImport = !request.privateKey.isNullOrBlank()

            if (isPrivateKeyImport) {
                coroutineScope {
                    val magicDeferred = async {
                        try {
                            val proxyAddress = blockchainService.getProxyAddress(request.walletAddress, WalletType.MAGIC).getOrNull()
                            if (proxyAddress != null) {
                                val balance = blockchainService.getWalletBalance(proxyAddress).getOrNull()
                                ProxyOptionDto(
                                    walletType = WalletType.MAGIC.value,
                                    proxyAddress = proxyAddress,
                                    descriptionKey = "accountImport.proxyOption.magic.description",
                                    availableBalance = balance?.availableBalance ?: "0",
                                    positionBalance = balance?.positionBalance ?: "0",
                                    totalBalance = balance?.totalBalance ?: "0",
                                    positionCount = balance?.positions?.size ?: 0,
                                    hasAssets = (balance?.availableBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                                            (balance?.positionBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                                            (balance?.positions?.isNotEmpty() == true),
                                    error = null
                                )
                            } else {
                                ProxyOptionDto(
                                    walletType = "magic",
                                    proxyAddress = "",
                                    descriptionKey = "accountImport.proxyOption.magic.description",
                                    availableBalance = "0",
                                    positionBalance = "0",
                                    totalBalance = "0",
                                    positionCount = 0,
                                    hasAssets = false,
                                    error = "获取 Magic 代理地址失败"
                                )
                            }
                        } catch (e: Exception) {
                            logger.warn("获取 Magic 代理地址或资产失败: ${e.message}", e)
                            ProxyOptionDto(
                                walletType = "magic",
                                proxyAddress = blockchainService.calculateMagicProxyAddress(request.walletAddress),
                                descriptionKey = "accountImport.proxyOption.magic.description",
                                availableBalance = "0",
                                positionBalance = "0",
                                totalBalance = "0",
                                positionCount = 0,
                                hasAssets = false,
                                error = "获取资产信息失败: ${e.message}"
                            )
                        }
                    }

                    val safeDeferred = async {
                        try {
                            val proxyAddress = blockchainService.getProxyAddress(request.walletAddress, WalletType.SAFE).getOrNull()
                            if (proxyAddress != null) {
                                val balance = blockchainService.getWalletBalance(proxyAddress).getOrNull()
                                ProxyOptionDto(
                                    walletType = WalletType.SAFE.value,
                                    proxyAddress = proxyAddress,
                                    descriptionKey = "accountImport.proxyOption.safe.description",
                                    availableBalance = balance?.availableBalance ?: "0",
                                    positionBalance = balance?.positionBalance ?: "0",
                                    totalBalance = balance?.totalBalance ?: "0",
                                    positionCount = balance?.positions?.size ?: 0,
                                    hasAssets = (balance?.availableBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                                            (balance?.positionBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                                            (balance?.positions?.isNotEmpty() == true),
                                    error = null
                                )
                            } else {
                                ProxyOptionDto(
                                    walletType = "safe",
                                    proxyAddress = "",
                                    descriptionKey = "accountImport.proxyOption.safe.description",
                                    availableBalance = "0",
                                    positionBalance = "0",
                                    totalBalance = "0",
                                    positionCount = 0,
                                    hasAssets = false,
                                    error = "获取 Safe 代理地址失败"
                                )
                            }
                        } catch (e: Exception) {
                            logger.warn("获取 Safe 代理地址或资产失败: ${e.message}", e)
                            ProxyOptionDto(
                                walletType = "safe",
                                proxyAddress = "",
                                descriptionKey = "accountImport.proxyOption.safe.description",
                                availableBalance = "0",
                                positionBalance = "0",
                                totalBalance = "0",
                                positionCount = 0,
                                hasAssets = false,
                                error = "获取资产信息失败: ${e.message}"
                            )
                        }
                    }

                    val magicOption = magicDeferred.await()
                    val safeOption = safeDeferred.await()
                    options.add(safeOption)
                    options.add(magicOption)
                }
            } else {
                try {
                    val proxyAddress = blockchainService.getProxyAddress(request.walletAddress, WalletType.SAFE).getOrNull()
                    if (proxyAddress != null) {
                        val balance = blockchainService.getWalletBalance(proxyAddress).getOrNull()
                        options.add(
                            ProxyOptionDto(
                                walletType = "safe",
                                proxyAddress = proxyAddress,
                                descriptionKey = "accountImport.proxyOption.safe.description",
                                availableBalance = balance?.availableBalance ?: "0",
                                positionBalance = balance?.positionBalance ?: "0",
                                totalBalance = balance?.totalBalance ?: "0",
                                positionCount = balance?.positions?.size ?: 0,
                                hasAssets = (balance?.availableBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                                        (balance?.positionBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                                        (balance?.positions?.isNotEmpty() == true),
                                error = null
                            )
                        )
                    } else {
                        options.add(
                            ProxyOptionDto(
                                walletType = "safe",
                                proxyAddress = "",
                                descriptionKey = "accountImport.proxyOption.safe.description",
                                availableBalance = "0",
                                positionBalance = "0",
                                totalBalance = "0",
                                positionCount = 0,
                                hasAssets = false,
                                error = "获取 Safe 代理地址失败"
                            )
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("获取 Safe 代理地址或资产失败: ${e.message}", e)
                    options.add(
                        ProxyOptionDto(
                            walletType = "safe",
                            proxyAddress = "",
                            descriptionKey = "accountImport.proxyOption.safe.description",
                            availableBalance = "0",
                            positionBalance = "0",
                            totalBalance = "0",
                            positionCount = 0,
                            hasAssets = false,
                            error = "获取资产信息失败: ${e.message}"
                        )
                    )
                }
            }

            Result.success(CheckProxyOptionsResponse(options = options))
        } catch (e: Exception) {
            logger.error("检查代理地址选项失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private val setupApprovalSpenders = mapOf(
        "CTF_CONTRACT" to PolymarketConstants.CTF_CONTRACT_ADDRESS,
        "CTF_EXCHANGE" to PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS,
        "NEG_RISK_EXCHANGE" to PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS,
        "NEG_RISK_ADAPTER" to PolymarketConstants.NEG_RISK_ADAPTER_ADDRESS
    )

    private val usdcDecimals = java.math.BigDecimal("1000000")

    private val unlimitedAllowance = BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935")

    /**
     * @return AccountSetupStatusDto
     */
    suspend fun checkAccountSetupStatus(accountId: Long): Result<AccountSetupStatusDto> {
        return try {
            if (accountId <= 0) {
                return Result.failure(IllegalArgumentException("账户 ID 无效"))
            }
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            val proxyAddress = account.proxyAddress
            if (proxyAddress.isBlank()) {
                return Result.success(
                    AccountSetupStatusDto(
                        proxyDeployed = false,
                        tradingEnabled = account.apiKey != null && account.apiSecret != null && account.apiPassphrase != null,
                        tokensApproved = false,
                        approvalDetails = null,
                        error = "代理地址为空"
                    )
                )
            }
            val proxyDeployed = blockchainService.isProxyDeployed(proxyAddress)
            val tradingEnabled = account.apiKey != null &&
                    account.apiSecret != null &&
                    account.apiPassphrase != null
            val approvalDetails = mutableMapOf<String, String>()
            var tokensApproved = true
            for ((name, spender) in setupApprovalSpenders) {
                val allowanceResult = blockchainService.getUsdcAllowance(proxyAddress, spender)
                val allowance = allowanceResult.getOrNull() ?: BigInteger.ZERO
                val displayAmount = if (allowance >= unlimitedAllowance) {
                    "unlimited"
                } else {
                    java.math.BigDecimal(allowance).divide(usdcDecimals, 6, java.math.RoundingMode.DOWN).toPlainString()
                }
                approvalDetails[name] = displayAmount
                if (allowance <= BigInteger.ZERO) {
                    tokensApproved = false
                }
            }

            Result.success(
                AccountSetupStatusDto(
                    proxyDeployed = proxyDeployed,
                    tradingEnabled = tradingEnabled,
                    tokensApproved = tokensApproved,
                    approvalDetails = approvalDetails,
                    error = null
                )
            )
        } catch (e: Exception) {
            logger.error("检查账户设置状态失败: accountId=$accountId, ${e.message}", e)
            Result.failure(e)
        }
    }

    private val setupStep1RedirectUrl = "https://polymarket.com/settings/wallet"

    suspend fun executeSetupStep(accountId: Long, step: Int): Result<ExecuteSetupStepResponse> {
        return try {
            if (accountId <= 0) {
                return Result.failure(IllegalArgumentException("账户 ID 无效"))
            }
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            when (step) {
                1 -> {
                    val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.MAGIC)
                    if (walletType == WalletType.MAGIC) {
                        Result.success(
                            ExecuteSetupStepResponse(
                                success = false,
                                redirectUrl = setupStep1RedirectUrl
                            )
                        )
                    } else {
                        val proxyAddress = account.proxyAddress
                        if (proxyAddress.isBlank()) {
                            return Result.failure(IllegalArgumentException("代理地址为空"))
                        }
                        val alreadyDeployed = blockchainService.isProxyDeployed(proxyAddress)
                        if (alreadyDeployed) {
                            Result.success(ExecuteSetupStepResponse(success = true))
                        } else {
                            val privateKey = decryptPrivateKey(account)
                            val builderCredentials = getBuilderCredentials(account)
                            val deployResult = relayClientService.deploySafeViaBuilderRelayer(
                                privateKey = privateKey,
                                proxyAddress = proxyAddress,
                                fromAddress = account.walletAddress,
                                builderCredentials = builderCredentials
                            )
                            deployResult.fold(
                                onSuccess = { txHash ->
                                    Result.success(
                                        ExecuteSetupStepResponse(
                                            success = true,
                                            transactionHash = txHash
                                        )
                                    )
                                },
                                onFailure = { e ->
                                    logger.error("Safe 部署失败: accountId=$accountId, ${e.message}", e)
                                    Result.failure(e)
                                }
                            )
                        }
                    }
                }
                2 -> {
                    val privateKey = decryptPrivateKey(account)
                    val result = apiKeyService.createOrDeriveApiKey(
                        privateKey = privateKey,
                        walletAddress = account.walletAddress,
                        chainId = 137L
                    )
                    if (result.isFailure) {
                        val e = result.exceptionOrNull()
                        logger.error("启用交易（API Key）失败: accountId=$accountId, ${e?.message}", e)
                        return Result.failure(e ?: IllegalStateException("获取 API Key 失败"))
                    }
                    val creds = result.getOrNull()
                        ?: return Result.failure(IllegalStateException("API Key 返回为空"))
                    val encryptedSecret = creds.secret.let { cryptoUtils.encrypt(it) }
                    val encryptedPassphrase = creds.passphrase.let { cryptoUtils.encrypt(it) }
                    val updated = account.copy(
                        apiKey = creds.apiKey,
                        apiSecret = encryptedSecret,
                        apiPassphrase = encryptedPassphrase,
                        updatedAt = System.currentTimeMillis()
                    )
                    val saved = accountRepository.save(updated)
                    publishAccountStateChanged(saved)
                    Result.success(ExecuteSetupStepResponse(success = true))
                }
                3 -> {
                    val proxyAddress = account.proxyAddress
                    if (proxyAddress.isBlank()) {
                        return Result.failure(IllegalArgumentException("代理地址为空，请先完成步骤1"))
                    }
                    val privateKey = decryptPrivateKey(account)
                    val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)
                    val builderCredentials = getBuilderCredentials(account)
                    val approveTxs = setupApprovalSpenders.values.map { spender ->
                        relayClientService.createUsdcApproveTx(spender, unlimitedAllowance)
                    }
                    val multiSendTx = relayClientService.createMultiSendTx(approveTxs)
                    val executeResult = relayClientService.execute(
                        privateKey = privateKey,
                        proxyAddress = proxyAddress,
                        safeTx = multiSendTx,
                        walletType = walletType,
                        builderCredentials = builderCredentials
                    )
                    executeResult.fold(
                        onSuccess = { txHash ->
                            Result.success(
                                ExecuteSetupStepResponse(
                                    success = true,
                                    transactionHash = txHash
                                )
                            )
                        },
                        onFailure = { e ->
                            logger.error("代币授权执行失败: accountId=$accountId, ${e.message}", e)
                            Result.failure(e)
                        }
                    )
                }
                else -> Result.failure(IllegalArgumentException("无效的步骤: $step，应为 1、2 或 3"))
            }
        } catch (e: Exception) {
            logger.error("执行设置步骤失败: accountId=$accountId, step=$step, ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    suspend fun updateAccount(request: AccountUpdateRequest): Result<AccountDto> {
        return try {
            val account = accountRepository.findById(request.accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))
            val updatedAccountName = resolveOptionalTextUpdate(account.accountName, request.accountName)
            val updatedRemark = resolveOptionalTextUpdate(account.remark, request.remark)
            val updatedIsEnabled = request.isEnabled ?: account.isEnabled
            val updatedBuilderApiKey = when (request.builderApiKey) {
                null -> account.builderApiKey
                else -> request.builderApiKey.trim().takeIf { it.isNotBlank() }?.let { cryptoUtils.encrypt(it) }
            }
            val updatedBuilderSecret = when (request.builderSecret) {
                null -> account.builderSecret
                else -> request.builderSecret.trim().takeIf { it.isNotBlank() }?.let { cryptoUtils.encrypt(it) }
            }
            val updatedBuilderPassphrase = when (request.builderPassphrase) {
                null -> account.builderPassphrase
                else -> request.builderPassphrase.trim().takeIf { it.isNotBlank() }?.let { cryptoUtils.encrypt(it) }
            }
            val updatedAutoRedeemEnabled = request.autoRedeemEnabled ?: account.autoRedeemEnabled

            val updated = account.copy(
                accountName = updatedAccountName,
                remark = updatedRemark,
                isDefault = account.isDefault,
                isEnabled = updatedIsEnabled,
                builderApiKey = updatedBuilderApiKey,
                builderSecret = updatedBuilderSecret,
                builderPassphrase = updatedBuilderPassphrase,
                autoRedeemEnabled = updatedAutoRedeemEnabled,
                updatedAt = System.currentTimeMillis()
            )

            val saved = accountRepository.save(updated)
            publishAccountStateChanged(saved)

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新账户失败", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun deleteAccount(accountId: Long): Result<Unit> {
        return try {
            val account = accountRepository.findById(accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))

            accountRepository.delete(account)
            publishAccountStateChanged(account)

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除账户失败", e)
            Result.failure(e)
        }
    }

    fun getAccountList(): Result<AccountListResponse> {
        return try {
            val accounts = accountRepository.findAllByOrderByCreatedAtAsc()
            val accountDtos = accounts.map { toBasicDto(it) }

            Result.success(
                AccountListResponse(
                    list = accountDtos,
                    total = accountDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询账户列表失败", e)
            Result.failure(e)
        }
    }

    suspend fun getAccountDetail(accountId: Long?): Result<AccountDto> {
        return try {
            if (accountId == null) {
                return Result.failure(IllegalArgumentException("账户ID不能为空"))
            }
            
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            Result.success(toDto(account))
        } catch (e: Exception) {
            logger.error("查询账户详情失败", e)
            Result.failure(e)
        }
    }

    suspend fun getAccountBalance(accountId: Long?): Result<AccountBalanceResponse> {
        return try {
            if (accountId == null) {
                return Result.failure(IllegalArgumentException("账户ID不能为空"))
            }

            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))
            if (account.proxyAddress.isBlank()) {
                logger.error("账户 ${account.id} 的代理地址为空，无法查询余额")
                return Result.failure(IllegalStateException("账户代理地址不存在，无法查询余额。请重新导入账户以获取代理地址"))
            }
            val balanceResult = runBlocking {
                blockchainService.getWalletBalance(account.proxyAddress)
            }

            balanceResult.map { walletBalance: WalletBalanceResponse ->
                AccountBalanceResponse(
                    availableBalance = walletBalance.availableBalance,
                    positionBalance = walletBalance.positionBalance,
                    totalBalance = walletBalance.totalBalance,
                    positions = walletBalance.positions
                )
            }
        } catch (e: Exception) {
            logger.error("查询账户余额失败", e)
            Result.failure(e)
        }
    }

    private fun toBasicDto(account: Account): AccountDto {
        val builderApiKeyDisplay = decryptOptionalBuilderValue(account.id, "Builder API Key", account.builderApiKey)
        val builderSecretDisplay = decryptOptionalBuilderValue(account.id, "Builder Secret", account.builderSecret)
        val builderPassphraseDisplay = decryptOptionalBuilderValue(account.id, "Builder Passphrase", account.builderPassphrase)
        return AccountDto(
            id = account.id!!,
            walletAddress = account.walletAddress,
            proxyAddress = account.proxyAddress,
            accountName = account.accountName,
            remark = account.remark,
            isEnabled = account.isEnabled,
            walletType = account.walletType,
            apiKeyConfigured = account.apiKey != null,
            apiSecretConfigured = account.apiSecret != null,
            apiPassphraseConfigured = account.apiPassphrase != null,
            builderConfigured = !builderApiKeyDisplay.isNullOrBlank() &&
                !builderSecretDisplay.isNullOrBlank() &&
                !builderPassphraseDisplay.isNullOrBlank(),
            builderApiKeyDisplay = builderApiKeyDisplay,
            builderSecretDisplay = builderSecretDisplay,
            builderPassphraseDisplay = builderPassphraseDisplay,
            autoRedeemEnabled = account.autoRedeemEnabled,
            totalOrders = null,
            totalPnl = null,
            activeOrders = null,
            completedOrders = null,
            positionCount = null
        )
    }

    private fun toDto(account: Account): AccountDto {
        return runBlocking {
            val statistics = getAccountStatistics(account)
            val builderApiKeyDisplay = decryptOptionalBuilderValue(account.id, "Builder API Key", account.builderApiKey)
            val builderSecretDisplay = decryptOptionalBuilderValue(account.id, "Builder Secret", account.builderSecret)
            val builderPassphraseDisplay = decryptOptionalBuilderValue(account.id, "Builder Passphrase", account.builderPassphrase)
            AccountDto(
                id = account.id!!,
                walletAddress = account.walletAddress,
                proxyAddress = account.proxyAddress,
                accountName = account.accountName,
                remark = account.remark,
                isEnabled = account.isEnabled,
                walletType = account.walletType,
                apiKeyConfigured = account.apiKey != null,
                apiSecretConfigured = account.apiSecret != null,
                apiPassphraseConfigured = account.apiPassphrase != null,
                builderConfigured = !builderApiKeyDisplay.isNullOrBlank() &&
                    !builderSecretDisplay.isNullOrBlank() &&
                    !builderPassphraseDisplay.isNullOrBlank(),
                builderApiKeyDisplay = builderApiKeyDisplay,
                builderSecretDisplay = builderSecretDisplay,
                builderPassphraseDisplay = builderPassphraseDisplay,
                autoRedeemEnabled = account.autoRedeemEnabled,
                totalOrders = statistics.totalOrders,
                totalPnl = statistics.totalPnl,
                activeOrders = statistics.activeOrders,
                completedOrders = statistics.completedOrders,
                positionCount = statistics.positionCount
            )
        }
    }

    private suspend fun getAccountStatistics(account: Account): AccountStatistics {
        return try {
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return AccountStatistics(
                    totalOrders = null,
                    totalPnl = null,
                    activeOrders = null,
                    completedOrders = null,
                    positionCount = null
                )
            }
            val apiKey = account.apiKey
            val apiSecret = decryptApiSecret(account)
            val apiPassphrase = decryptApiPassphrase(account)
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)
            val activeOrdersResult = try {
                var totalActiveOrders = 0L
                var nextCursor: String? = null
                do {
                    val response = clobApi.getActiveOrders(
                        id = null,
                        market = null,
                        asset_id = null,
                        next_cursor = nextCursor
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val ordersResponse = response.body()!!
                        totalActiveOrders += ordersResponse.data.size
                        nextCursor = ordersResponse.next_cursor
                    } else {
                        break
                    }
                } while (nextCursor != null && nextCursor.isNotEmpty())

                Result.success(totalActiveOrders)
            } catch (e: Exception) {
                logger.warn("查询活跃订单失败: ${e.message}", e)
                Result.failure(e)
            }
            val completedOrdersResult = try {
                var allTrades = mutableListOf<TradeResponse>()
                var nextCursor: String? = null
                do {
                    val response = clobApi.getTrades(
                        maker_address = account.proxyAddress,
                        next_cursor = nextCursor
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val tradesResponse = response.body()!!
                        allTrades.addAll(tradesResponse.data)
                        nextCursor = tradesResponse.next_cursor
                    } else {
                        break
                    }
                } while (nextCursor != null && nextCursor.isNotEmpty())
                val completedOrdersCount = allTrades.size.toLong()

                Result.success(completedOrdersCount)
            } catch (e: Exception) {
                logger.warn("查询交易记录失败: ${e.message}", e)
                Result.failure(e)
            }
            val positionsResult = try {
                val positions = blockchainService.getPositions(account.proxyAddress)
                if (positions.isSuccess) {
                    val positionList = positions.getOrNull() ?: emptyList()
                    val totalRealizedPnl = positionList.sumOf { pos ->
                        pos.realizedPnl?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    }
                    val positionCount = positionList.count { pos ->
                        val size = pos.size?.toSafeBigDecimal() ?: BigDecimal.ZERO
                        size != BigDecimal.ZERO
                    }
                    Result.success(Pair(totalRealizedPnl.toPlainString(), positionCount.toLong()))
                } else {
                    Result.failure(Exception("查询仓位信息失败"))
                }
            } catch (e: Exception) {
                logger.warn("查询仓位信息失败: ${e.message}", e)
                Result.failure(e)
            }

            val activeOrders = activeOrdersResult.getOrNull() ?: 0L
            val completedOrders = completedOrdersResult.getOrNull() ?: 0L
            val totalOrders = activeOrders + completedOrders
            val (totalPnl, positionCount) = positionsResult.getOrNull() ?: Pair(null, null)

            AccountStatistics(
                totalOrders = totalOrders,
                totalPnl = totalPnl,
                activeOrders = activeOrders,
                completedOrders = completedOrders,
                positionCount = positionCount
            )
        } catch (e: Exception) {
            logger.warn("获取账户统计数据失败: ${e.message}", e)
            AccountStatistics(
                totalOrders = null,
                totalPnl = null,
                activeOrders = null,
                completedOrders = null,
                positionCount = null
            )
        }
    }

    private data class AccountStatistics(
        val totalOrders: Long?,
        val totalPnl: String?,
        val activeOrders: Long?,
        val completedOrders: Long?,
        val positionCount: Long?
    )

    private fun resolveOptionalTextUpdate(current: String?, requestValue: String?): String? {
        return if (requestValue == null) {
            current
        } else {
            requestValue.trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun isValidWalletAddress(address: String): Boolean {
        return address.startsWith("0x") && address.length == 42 && address.matches(Regex("^0x[0-9a-fA-F]{40}$"))
    }

    private fun isValidPrivateKey(privateKey: String): Boolean {
        val cleanKey = if (privateKey.startsWith("0x")) privateKey.substring(2) else privateKey
        return cleanKey.length == 64 && cleanKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            throw RuntimeException("解密私钥失败: ${e.message}", e)
        }
    }

    private fun decryptOptionalBuilderValue(accountId: Long?, fieldName: String, encryptedValue: String?): String? {
        val value = encryptedValue?.takeIf { it.isNotBlank() } ?: return null
        return try {
            cryptoUtils.decrypt(value)
        } catch (e: Exception) {
            logger.error("解密 $fieldName 失败: accountId=$accountId", e)
            null
        }
    }

    fun hasBuilderConfig(account: Account): Boolean {
        val builderApiKey = decryptOptionalBuilderValue(account.id, "Builder API Key", account.builderApiKey)
        val builderSecret = decryptOptionalBuilderValue(account.id, "Builder Secret", account.builderSecret)
        val builderPassphrase = decryptOptionalBuilderValue(account.id, "Builder Passphrase", account.builderPassphrase)
        return !builderApiKey.isNullOrBlank() &&
            !builderSecret.isNullOrBlank() &&
            !builderPassphrase.isNullOrBlank()
    }

    private fun getBuilderCredentials(account: Account): RelayClientService.BuilderCredentials? {
        val builderApiKey = decryptOptionalBuilderValue(account.id, "Builder API Key", account.builderApiKey)
        val builderSecret = decryptOptionalBuilderValue(account.id, "Builder Secret", account.builderSecret)
        val builderPassphrase = decryptOptionalBuilderValue(account.id, "Builder Passphrase", account.builderPassphrase)
        if (builderApiKey.isNullOrBlank() || builderSecret.isNullOrBlank() || builderPassphrase.isNullOrBlank()) {
            return null
        }
        return RelayClientService.BuilderCredentials(
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )
    }

    suspend fun runWcolUnwrapForAllAccounts() {
        val accounts = accountRepository.findAllByOrderByCreatedAtAsc()
        if (accounts.isEmpty()) return
        for (account in accounts) {
            try {
                val privateKey = decryptPrivateKey(account)
                val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)
                val builderCredentials = getBuilderCredentials(account)
                if (walletType == WalletType.MAGIC && builderCredentials == null) {
                    logger.debug("轮询解包 WCOL 跳过 Magic 账户，Builder 未配置: accountId=${account.id}")
                    continue
                }
                blockchainService.unwrapWcolForProxy(
                    privateKey = privateKey,
                    proxyAddress = account.proxyAddress,
                    walletType = walletType,
                    builderCredentials = builderCredentials
                ).fold(
                    onSuccess = { txHash ->
                        if (txHash != null) {
                            logger.info("轮询解包 WCOL: accountId=${account.id}, proxy=${account.proxyAddress.take(10)}..., txHash=$txHash")
                        }
                    },
                    onFailure = { e ->
                        logger.warn("轮询解包 WCOL 失败 accountId=${account.id}: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                logger.warn("轮询解包 WCOL 跳过 accountId=${account.id}: ${e.message}")
            }
        }
    }

    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }
    
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    suspend fun getAllPositions(): Result<PositionListResponse> {
        return try {
            val accounts = accountRepository.findAll()
            val currentPositions = mutableListOf<AccountPositionDto>()
            val historyPositions = mutableListOf<AccountPositionDto>()
            accounts.forEach { account ->
                if (account.proxyAddress.isNotBlank()) {
                    try {
                        val positionsResult = blockchainService.getPositions(account.proxyAddress)
                        if (positionsResult.isSuccess) {
                            val positions = positionsResult.getOrNull() ?: emptyList()
                            positions.forEach { pos ->
                                val currentValue = pos.currentValue?.toSafeBigDecimal() ?: BigDecimal.ZERO
                                val curPrice = pos.curPrice?.toSafeBigDecimal() ?: BigDecimal.ZERO
                                val isCurrent = !currentValue.eq(BigDecimal.ZERO) && !curPrice.eq(BigDecimal.ZERO)
                                val sizeDecimal = pos.size?.let { 
                                    BigDecimal.valueOf(it)
                                } ?: BigDecimal.ZERO
                                val displayQuantity = sizeDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
                                val originalQuantity = sizeDecimal.toPlainString()

                                val positionDto = AccountPositionDto(
                                    accountId = account.id!!,
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    proxyAddress = account.proxyAddress,
                                    marketId = pos.conditionId ?: "",
                                    marketTitle = pos.title ?: "",
                                    marketSlug = pos.slug ?: "",
                                    eventSlug = pos.eventSlug,
                                    marketIcon = pos.icon,
                                    side = pos.outcome ?: "",
                                    outcomeIndex = pos.outcomeIndex,
                                    quantity = displayQuantity,
                                    originalQuantity = originalQuantity,
                                    avgPrice = pos.avgPrice?.toString() ?: "0",
                                    currentPrice = pos.curPrice?.toString() ?: "0",
                                    currentValue = pos.currentValue?.toString() ?: "0",
                                    initialValue = pos.initialValue?.toString() ?: "0",
                                    pnl = pos.cashPnl?.toString() ?: "0",
                                    percentPnl = pos.percentPnl?.toString() ?: "0",
                                    realizedPnl = pos.realizedPnl?.toString(),
                                    percentRealizedPnl = pos.percentRealizedPnl?.toString(),
                                    redeemable = pos.redeemable ?: false,
                                    mergeable = pos.mergeable ?: false,
                                    endDate = pos.endDate,
                                    isCurrent = isCurrent
                                )
                                if (isCurrent) {
                                    currentPositions.add(positionDto)
                                } else {
                                    historyPositions.add(positionDto)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("查询账户 ${account.id} 仓位失败: ${e.message}", e)
                    }
                }
            }
            Result.success(
                PositionListResponse(
                    currentPositions = currentPositions,
                    historyPositions = historyPositions
                )
            )
        } catch (e: Exception) {
            logger.error("查询所有仓位失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCurrentPositionsForAccount(accountId: Long): Result<List<AccountPositionDto>> {
        return try {
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Account not found: $accountId"))
            if (account.proxyAddress.isBlank()) {
                return Result.success(emptyList())
            }
            val positions = blockchainService.getPositions(account.proxyAddress).getOrElse { error ->
                return Result.failure(error)
            }
            Result.success(
                positions.mapNotNull { position ->
                    position.toAccountPositionDto(account)?.takeIf { it.isCurrent }
                }
            )
        } catch (e: Exception) {
            logger.error("Failed to query current positions for account: accountId=$accountId, error=${e.message}", e)
            Result.failure(e)
        }
    }

    private fun PositionResponse.toAccountPositionDto(account: Account): AccountPositionDto? {
        val accountId = account.id ?: return null
        val currentValueDecimal = currentValue?.toSafeBigDecimal() ?: BigDecimal.ZERO
        val currentPriceDecimal = curPrice?.toSafeBigDecimal() ?: BigDecimal.ZERO
        val isCurrent = !currentValueDecimal.eq(BigDecimal.ZERO) && !currentPriceDecimal.eq(BigDecimal.ZERO)
        val sizeDecimal = size?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO
        return AccountPositionDto(
            accountId = accountId,
            accountName = account.accountName,
            walletAddress = account.walletAddress,
            proxyAddress = account.proxyAddress,
            marketId = conditionId ?: "",
            marketTitle = title ?: "",
            marketSlug = slug ?: "",
            eventSlug = eventSlug,
            marketIcon = icon,
            side = outcome ?: "",
            outcomeIndex = outcomeIndex,
            quantity = sizeDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString(),
            originalQuantity = sizeDecimal.toPlainString(),
            avgPrice = avgPrice?.toString() ?: "0",
            currentPrice = curPrice?.toString() ?: "0",
            currentValue = currentValue?.toString() ?: "0",
            initialValue = initialValue?.toString() ?: "0",
            pnl = cashPnl?.toString() ?: "0",
            percentPnl = percentPnl?.toString() ?: "0",
            realizedPnl = realizedPnl?.toString(),
            percentRealizedPnl = percentRealizedPnl?.toString(),
            redeemable = redeemable ?: false,
            mergeable = mergeable ?: false,
            endDate = endDate,
            isCurrent = isCurrent
        )
    }

    suspend fun sellPosition(request: PositionSellRequest): Result<PositionSellResponse> {
        return try {
            val account = accountRepository.findById(request.accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return Result.failure(IllegalStateException("账户未配置API凭证，无法创建订单"))
            }
            if (request.percent.isNullOrBlank() && request.quantity.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("必须提供卖出数量(quantity)或卖出百分比(percent)"))
            }
            
            if (!request.percent.isNullOrBlank() && !request.quantity.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("不能同时提供卖出数量(quantity)和卖出百分比(percent)"))
            }
            val percentDecimal = if (!request.percent.isNullOrBlank()) {
                try {
                    val percent = request.percent!!.toSafeBigDecimal()
                    if (percent <= BigDecimal.ZERO || percent > BigDecimal.valueOf(100)) {
                        return Result.failure(IllegalArgumentException("卖出百分比必须在 0-100 之间"))
                    }
                    percent
                } catch (e: Exception) {
                    return Result.failure(IllegalArgumentException("卖出百分比格式不正确: ${e.message}"))
                }
            } else {
                null
            }
            val positionsResult = getAllPositions()
            val (_, originalQuantity) = positionsResult.fold(
                onSuccess = { positionListResponse ->
                    val position = positionListResponse.currentPositions.find {
                        it.accountId == request.accountId &&
                                it.marketId == request.marketId &&
                                it.side == request.side
                    }

                    if (position == null) {
                        return Result.failure(IllegalArgumentException("仓位不存在"))
                    }
                    val originalQty = if (position.originalQuantity != null) {
                        position.originalQuantity.toSafeBigDecimal()
                    } else {
                        val blockchainPositionsResult = blockchainService.getPositions(account.proxyAddress)
                        if (blockchainPositionsResult.isSuccess) {
                            val blockchainPos = blockchainPositionsResult.getOrNull()?.find {
                                it.conditionId == request.marketId && it.outcome == request.side
                            }
                            blockchainPos?.size?.let { BigDecimal.valueOf(it) } ?: position.quantity.toSafeBigDecimal()
                        } else {
                            position.quantity.toSafeBigDecimal()
                        }
                    }
                    
                    Pair(position, originalQty)
                },
                onFailure = { e ->
                    return Result.failure(Exception("查询仓位失败: ${e.message}"))
                }
            )
            val sellQuantity = if (percentDecimal != null) {
                originalQuantity.multiply(percentDecimal)
                    .divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.DOWN)
            } else {
                request.quantity!!.toSafeBigDecimal()
            }
            if (sellQuantity <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("卖出数量必须大于0"))
            }

            if (sellQuantity > originalQuantity) {
                return Result.failure(IllegalArgumentException("卖出数量不能超过持仓数量"))
            }
            val tokenIdResult = if (request.outcomeIndex != null) {
                blockchainService.getTokenId(request.marketId, request.outcomeIndex)
            } else {
                logger.warn("缺少 outcomeIndex 参数，无法计算 tokenId: marketId=${request.marketId}, side=${request.side}")
                Result.failure<String>(IllegalArgumentException("缺少 outcomeIndex 参数，无法计算 tokenId。请提供 outcomeIndex 参数"))
            }
            val tokenId = tokenIdResult.getOrNull()

            if (tokenId == null) {
                logger.warn("无法获取 tokenId，将使用 market 参数: conditionId=${request.marketId}, side=${request.side}, outcomeIndex=${request.outcomeIndex}, error=${tokenIdResult.exceptionOrNull()?.message}")
            }
            if (tokenId == null) {
                return Result.failure(IllegalStateException("无法获取 tokenId，无法创建订单。请确保已配置 Ethereum RPC URL 或提供 outcomeIndex 参数"))
            }
            val sellPrice = if (request.orderType == "MARKET") {
                try {
                    getOptimalPriceFromOrderbook(tokenId, isSellOrder = true)
                } catch (e: IllegalStateException) {
                    logger.error("无法获取订单表最优价: ${e.message}", e)
                    return Result.failure(IllegalStateException("无法获取订单表最优价: ${e.message}"))
                }
            } else {
                request.price ?: return Result.failure(IllegalArgumentException("限价订单必须提供价格"))
            }
            val priceDecimal = sellPrice.toSafeBigDecimal()
            if (priceDecimal <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("价格必须大于0"))
            }
            val orderType = when (request.orderType) {
                "MARKET" -> "FAK"
                "LIMIT" -> "GTC"   // Good-Til-Cancelled
                else -> "GTC"
            }
            val decryptedPrivateKey = decryptPrivateKey(account)
            val signedOrder = try {
                orderSigningService.createAndSignOrder(
                    privateKey = decryptedPrivateKey,
                    makerAddress = account.proxyAddress,
                    tokenId = tokenId,
                    side = "SELL",
                    price = sellPrice,
                    size = sellQuantity.toPlainString(),
                    signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
                )
            } catch (e: Exception) {
                logger.error("创建并签名订单失败", e)
                return Result.failure(Exception("创建并签名订单失败: ${e.message}"))
            }

            val newOrderRequest = com.wrbug.polymarketbot.api.NewOrderRequest(
                order = signedOrder,
                owner = account.apiKey,  // API Key
                orderType = orderType,
                deferExec = false
            )
            val apiSecret = try {
                decryptApiSecret(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败: accountId=${account.id}", e)
                return Result.failure(IllegalStateException("解密 API 凭证失败: ${e.message}"))
            }
            val apiPassphrase = try {
                decryptApiPassphrase(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败: accountId=${account.id}", e)
                return Result.failure(IllegalStateException("解密 API 凭证失败: ${e.message}"))
            }

            val clobApi = retrofitFactory.createClobApi(
                account.apiKey,
                apiSecret,
                apiPassphrase,
                account.walletAddress
            )


            val orderResponse = clobApi.createOrder(newOrderRequest)

            if (orderResponse.isSuccessful && orderResponse.body() != null) {
                val response = orderResponse.body()!!
                if (response.success) {
                    val orderId = response.orderId ?: ""
                    notificationScope.launch {
                        try {
                            val market = marketService.getMarket(request.marketId)
                            val marketTitle = market?.title ?: request.marketId
                            val marketSlug = market?.eventSlug
                            val locale = try {
                                org.springframework.context.i18n.LocaleContextHolder.getLocale()
                            } catch (e: Exception) {
                                java.util.Locale("zh", "CN")
                            }
                            val orderTime = System.currentTimeMillis()
                            val availableBalance = try {
                                blockchainService.getUsdcBalance(account.walletAddress, account.proxyAddress).getOrNull()
                            } catch (e: Exception) {
                                logger.warn("查询可用余额失败: accountId=${account.id}, ${e.message}")
                                null
                            }

                            telegramNotificationService?.sendOrderSuccessNotification(
                                orderId = orderId,
                                marketTitle = marketTitle,
                                marketId = request.marketId,
                                marketSlug = marketSlug,
                                side = "SELL",
                                outcome = request.side,
                                price = sellPrice,
                                size = sellQuantity.toPlainString(),
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                clobApi = clobApi,
                                apiKey = account.apiKey,
                                apiSecret = try { cryptoUtils.decrypt(account.apiSecret!!) } catch (e: Exception) { null },
                                apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase!!) } catch (e: Exception) { null },
                                walletAddressForApi = account.walletAddress,
                                locale = locale,
                                orderTime = orderTime,
                                availableBalance = availableBalance
                            )
                        } catch (e: Exception) {
                            logger.warn("发送订单成功通知失败: ${e.message}", e)
                        }
                    }
                    
                    Result.success(
                        PositionSellResponse(
                            orderId = orderId,
                            marketId = request.marketId,
                            side = request.side,
                            orderType = request.orderType,
                            quantity = sellQuantity.toPlainString(),
                            price = if (request.orderType == "LIMIT") sellPrice else null,
                            status = "pending",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val errorMsg = response.getErrorMessage()
                    val fullErrorMsg = "创建订单失败: accountId=${account.id}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, price=${if (request.orderType == "LIMIT") sellPrice else "MARKET"}, quantity=${sellQuantity.toPlainString()}, errorMsg=$errorMsg"
                    logger.error(fullErrorMsg)
                    notificationScope.launch {
                        try {
                            val market = marketService.getMarket(request.marketId)
                            val marketTitle = market?.title ?: request.marketId
                            val marketSlug = market?.eventSlug
                            val locale = try {
                                org.springframework.context.i18n.LocaleContextHolder.getLocale()
                            } catch (e: Exception) {
                                java.util.Locale("zh", "CN")
                            }

                            telegramNotificationService?.sendOrderFailureNotification(
                                marketTitle = marketTitle,
                                marketId = request.marketId,
                                marketSlug = marketSlug,
                                side = request.side,
                                outcome = null,
                                price = if (request.orderType == "LIMIT") sellPrice.toString() else "MARKET",
                                size = sellQuantity.toString(),
                                errorMessage = errorMsg,
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                locale = locale
                            )
                        } catch (e: Exception) {
                            logger.warn("发送订单失败通知失败: ${e.message}", e)
                        }
                    }
                    
                    Result.failure(Exception(fullErrorMsg))
                }
            } else {
                val errorBody = try {
                    orderResponse.errorBody()?.string()
                } catch (e: Exception) {
                    null
                }
                val apiError = try {
                    (errorBody?.fromJson<JsonObject>()?.get("error") as? JsonPrimitive)?.asString
                } catch (e: Exception) {
                    null
                }
                
                val fullErrorMsg = "创建订单失败: accountId=${account.id}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, price=${if (request.orderType == "LIMIT") sellPrice else "MARKET"}, quantity=${sellQuantity.toPlainString()}, code=${orderResponse.code()}, message=${orderResponse.message()}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                logger.error(fullErrorMsg)
                notificationScope.launch {
                    try {
                        val market = marketService.getMarket(request.marketId)
                        val marketTitle = market?.title ?: request.marketId
                        val marketSlug = market?.eventSlug
                        val locale = try {
                            org.springframework.context.i18n.LocaleContextHolder.getLocale()
                        } catch (e: Exception) {
                            java.util.Locale("zh", "CN")
                        }
                        val errorMsg = apiError 
                            ?: orderResponse.body()?.getErrorMessage() 
                            ?: "创建订单失败 (HTTP ${orderResponse.code()})"

                        telegramNotificationService?.sendOrderFailureNotification(
                            marketTitle = marketTitle,
                            marketId = request.marketId,
                            marketSlug = marketSlug,
                            side = request.side,
                            outcome = null,
                            price = if (request.orderType == "LIMIT") sellPrice.toString() else "MARKET",
                            size = sellQuantity.toString(),
                            errorMessage = errorMsg,
                            accountName = account.accountName,
                            walletAddress = account.walletAddress,
                            locale = locale
                        )
                    } catch (e: Exception) {
                        logger.warn("发送订单失败通知失败: ${e.message}", e)
                    }
                }
                
                Result.failure(Exception(fullErrorMsg))
            }
        } catch (e: Exception) {
            val fullErrorMsg = "卖出仓位异常: accountId=${request.accountId}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, error=${e.message}"
            logger.error(fullErrorMsg, e)
            Result.failure(Exception(fullErrorMsg))
        }
    }

    private suspend fun getOptimalPriceFromOrderbook(tokenId: String, isSellOrder: Boolean): String {
        return clobService.getOptimalPrice(
            tokenId = tokenId,
            isSellOrder = isSellOrder,
            buyPriceAdjustment = BUY_PRICE_ADJUSTMENT,
            sellPriceAdjustment = SELL_PRICE_ADJUSTMENT
        )
    }

    suspend fun getMarketPrice(marketId: String, outcomeIndex: Int? = null): Result<MarketPriceResponse> {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = listOf(marketId))

            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                val market = markets.firstOrNull()

                if (market != null) {
                    var bestBid = market.bestBid?.toString()
                    var bestAsk = market.bestAsk?.toString()
                    var lastPrice = market.lastTradePrice?.toString()
                    if (outcomeIndex != null && outcomeIndex > 0) {
                        val outcomes = jsonUtils.parseStringArray(market.outcomes)
                        if (outcomes.size == 2) {
                            val firstOutcomeBestBid = bestBid
                            val firstOutcomeBestAsk = bestAsk
                            bestBid = firstOutcomeBestAsk?.let { 
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                            bestAsk = firstOutcomeBestBid?.let { 
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                            lastPrice = lastPrice?.let {
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                        }
                    }
                    val midpoint = if (bestBid != null && bestAsk != null) {
                        val bid = bestBid.toSafeBigDecimal()
                        val ask = bestAsk.toSafeBigDecimal()
                        bid.add(ask).divide(BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP).toString()
                    } else {
                        null
                    }
                    val currentPrice = lastPrice ?: bestBid ?: midpoint ?: "0"

                    Result.success(
                        MarketPriceResponse(
                            marketId = marketId,
                            currentPrice = currentPrice
                        )
                    )
                } else {
                    Result.failure(Exception("未找到市场信息: $marketId"))
                }
            } else {
                Result.failure(Exception("获取市场价格失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取市场价格异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getRedeemablePositionsSummary(accountId: Long? = null): Result<RedeemablePositionsSummary> {
        return try {
            val positionsResult = getAllPositions()
            positionsResult.fold(
                onSuccess = { positionListResponse ->
                    val redeemablePositions = positionListResponse.currentPositions.filter { it.redeemable }
                    val filteredPositions = if (accountId != null) {
                        redeemablePositions.filter { it.accountId == accountId }
                    } else {
                        redeemablePositions
                    }
                    val totalValue = filteredPositions.fold(BigDecimal.ZERO) { sum, pos ->
                        sum.add(pos.quantity.toSafeBigDecimal())
                    }
                    val redeemableInfoList = filteredPositions.map { pos ->
                        com.wrbug.polymarketbot.dto.RedeemablePositionInfo(
                            accountId = pos.accountId,
                            accountName = pos.accountName,
                            marketId = pos.marketId,
                            marketTitle = pos.marketTitle,
                            side = pos.side,
                            outcomeIndex = pos.outcomeIndex ?: 0,
                            quantity = pos.quantity,
                            value = pos.quantity
                        )
                    }

                    Result.success(
                        com.wrbug.polymarketbot.dto.RedeemablePositionsSummary(
                            totalCount = redeemableInfoList.size,
                            totalValue = totalValue.toPlainString(),
                            positions = redeemableInfoList
                        )
                    )
                },
                onFailure = { e ->
                    Result.failure(Exception("查询仓位失败: ${e.message}"))
                }
            )
        } catch (e: Exception) {
            logger.error("获取可赎回仓位统计失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun redeemPositions(request: PositionRedeemRequest): Result<PositionRedeemResponse> {
        return try {
            if (request.positions.isEmpty()) {
                return Result.failure(IllegalArgumentException("赎回仓位列表不能为空"))
            }
            val positionsResult = getAllPositions()
            val allPositions = positionsResult.getOrElse {
                return Result.failure(Exception("查询仓位失败: ${it.message}"))
            }
            val positionsByAccount = request.positions.groupBy { it.accountId }
            val accounts = mutableMapOf<Long, Account>()
            for (accountId in positionsByAccount.keys) {
                val account = accountRepository.findById(accountId).orElse(null)
                    ?: return Result.failure(IllegalArgumentException("账户不存在: $accountId"))
                accounts[accountId] = account
            }
            val accountRedeemData = mutableMapOf<Long, MutableList<Pair<AccountPositionDto, BigInteger>>>()
            val accountRedeemedInfo =
                mutableMapOf<Long, MutableList<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>>()

            for ((accountId, requestItems) in positionsByAccount) {
                val accountPositions = mutableListOf<Pair<AccountPositionDto, BigInteger>>()
                val accountInfo = mutableListOf<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>()

                for (requestItem in requestItems) {
                    val position = allPositions.currentPositions.find {
                        it.accountId == accountId &&
                                it.marketId == requestItem.marketId &&
                                it.outcomeIndex == requestItem.outcomeIndex
                    }

                    if (position == null) {
                        return Result.failure(IllegalArgumentException("仓位不存在: accountId=$accountId, marketId=${requestItem.marketId}, outcomeIndex=${requestItem.outcomeIndex}"))
                    }

                    if (!position.redeemable) {
                        return Result.failure(IllegalStateException("仓位不可赎回: accountId=$accountId, marketId=${requestItem.marketId}, outcomeIndex=${requestItem.outcomeIndex}"))
                    }
                    val indexSet = BigInteger.TWO.pow(requestItem.outcomeIndex)
                    accountPositions.add(Pair(position, indexSet))

                    accountInfo.add(
                        com.wrbug.polymarketbot.dto.RedeemedPositionInfo(
                            marketId = position.marketId,
                            side = position.side,
                            outcomeIndex = requestItem.outcomeIndex,
                            quantity = position.quantity,
                            value = position.quantity
                        )
                    )
                }

                accountRedeemData[accountId] = accountPositions
                accountRedeemedInfo[accountId] = accountInfo
            }
            val accountTransactions = mutableListOf<com.wrbug.polymarketbot.dto.AccountRedeemTransaction>()
            var totalRedeemedValue = BigDecimal.ZERO

            for ((accountId, positions) in accountRedeemData) {
                val account = accounts[accountId]!!
                val redeemedInfo = accountRedeemedInfo[accountId]!!
                val positionsByMarket = positions.groupBy { it.first.marketId }
                val walletTypeEnum = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)
                val builderCredentials = getBuilderCredentials(account)
                if (walletTypeEnum == WalletType.MAGIC && builderCredentials == null) {
                    return Result.failure(
                        IllegalStateException("账户 $accountId 未配置 Builder，无法执行 Magic 账户赎回")
                    )
                }
                if (walletTypeEnum == WalletType.MAGIC && relayClientService.isBuilderRelayerQuotaBlocked()) {
                    val remaining = relayClientService.getBuilderRelayerQuotaBlockedRemainingSeconds()
                    return Result.failure(
                        IllegalStateException("Builder Relayer 配额冷却中，请稍后重试: ${remaining}s")
                    )
                }
                val decryptedPrivateKey = decryptPrivateKey(account)
                var lastTxHash: String? = null
                if (walletTypeEnum == WalletType.SAFE && positionsByMarket.size > 1) {
                    val redeemRequests = mutableListOf<Triple<String, List<BigInteger>, Boolean>>()
                    for ((marketId, marketPositions) in positionsByMarket) {
                        val indexSets = marketPositions.map { it.second }
                        val isNegRisk = marketService.getNegRiskByConditionId(marketId) == true
                        redeemRequests.add(Triple(marketId, indexSets, isNegRisk))
                    }

                    logger.info("账户 $accountId: 使用 MultiSend 批量赎回 ${redeemRequests.size} 个市场")

                    val redeemResult = blockchainService.redeemPositionsBatch(
                        privateKey = decryptedPrivateKey,
                        proxyAddress = account.proxyAddress,
                        redeemRequests = redeemRequests,
                        walletType = walletTypeEnum,
                        builderCredentials = builderCredentials
                    )

                    redeemResult.fold(
                        onSuccess = { txHash ->
                            lastTxHash = txHash
                        },
                        onFailure = { e ->
                            logger.error("账户 $accountId MultiSend 批量赎回失败: ${e.message}", e)
                            return Result.failure(Exception("赎回失败: 账户 $accountId - ${e.message}"))
                        }
                    )
                } else {
                    for ((marketId, marketPositions) in positionsByMarket) {
                        val indexSets = marketPositions.map { it.second }
                        val isNegRisk = marketService.getNegRiskByConditionId(marketId) == true

                        val redeemResult = blockchainService.redeemPositions(
                            privateKey = decryptedPrivateKey,
                            proxyAddress = account.proxyAddress,
                            conditionId = marketId,
                            indexSets = indexSets,
                            isNegRisk = isNegRisk,
                            walletType = walletTypeEnum,
                            builderCredentials = builderCredentials
                        )

                        redeemResult.fold(
                            onSuccess = { txHash ->
                                lastTxHash = txHash
                            },
                            onFailure = { e ->
                                logger.error("账户 $accountId 市场 $marketId 赎回失败: ${e.message}", e)
                                return Result.failure(Exception("赎回失败: 账户 $accountId 市场 $marketId - ${e.message}"))
                            }
                    )
                }
                }
                val accountTotalValue = redeemedInfo.fold(BigDecimal.ZERO) { sum, info ->
                    sum.add(info.value.toSafeBigDecimal())
                }
                totalRedeemedValue = totalRedeemedValue.add(accountTotalValue)
                accountTransactions.add(
                    com.wrbug.polymarketbot.dto.AccountRedeemTransaction(
                        accountId = accountId,
                        accountName = account.accountName,
                        transactionHash = lastTxHash ?: "",
                        positions = redeemedInfo
                    )
                )
            }
            notificationScope.launch {
                try {
                    val locale = try {
                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    } catch (e: Exception) {
                        java.util.Locale("zh", "CN")
                    }
                    for (transaction in accountTransactions) {
                        val account = accounts[transaction.accountId]
                        if (account != null) {
                            val availableBalance = try {
                                blockchainService.getUsdcBalance(account.walletAddress, account.proxyAddress).getOrNull()
                            } catch (e: Exception) {
                                logger.warn("查询可用余额失败: accountId=${account.id}, ${e.message}")
                                null
                            }
                            val accountTotalValue = transaction.positions.fold(BigDecimal.ZERO) { sum, info ->
                                sum.add(info.value.toSafeBigDecimal())
                            }
                            if (accountTotalValue.gt(BigDecimal.ZERO)) {
                                telegramNotificationService?.sendRedeemNotification(
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    transactionHash = transaction.transactionHash,
                                    totalRedeemedValue = accountTotalValue.toPlainString(),
                                    positions = transaction.positions,
                                    locale = locale,
                                    availableBalance = availableBalance
                                )
                            } else {
                                telegramNotificationService?.sendRedeemNoReturnNotification(
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    transactionHash = transaction.transactionHash,
                                    positions = transaction.positions,
                                    locale = locale,
                                    availableBalance = availableBalance
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("发送赎回推送通知失败: ${e.message}", e)
                }
            }
            Result.success(
                com.wrbug.polymarketbot.dto.PositionRedeemResponse(
                    transactions = accountTransactions,
                    totalRedeemedValue = totalRedeemedValue.toPlainString(),
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            logger.error("赎回仓位异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun hasActiveOrders(account: Account): Boolean {
        return try {
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return false
            }
            val apiKey = account.apiKey
            val apiSecret = decryptApiSecret(account)
            val apiPassphrase = decryptApiPassphrase(account)
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)
            val response = clobApi.getActiveOrders(
                id = null,
                market = null,
                asset_id = null,
                next_cursor = null
            )

            if (response.isSuccessful && response.body() != null) {
                val ordersResponse = response.body()!!
                val hasOrders = ordersResponse.data.isNotEmpty()
                hasOrders
            } else {
                logger.warn("查询活跃订单失败: ${response.code()} ${response.message()}，允许删除账户")
                false
            }
        } catch (e: Exception) {
            logger.warn("检查活跃订单异常: ${e.message}，允许删除账户", e)
            false
        }
    }
    private fun publishAccountStateChanged(account: Account) {
        val accountId = account.id ?: return
        eventPublisher.publishEvent(
            AccountStateChangedEvent(
                source = this,
                accountId = accountId,
                walletAddress = account.walletAddress
            )
        )
    }
}
