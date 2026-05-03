package com.wrbug.polymarketbot.service.common

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.ValueResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.PositionDto
import com.wrbug.polymarketbot.dto.WalletBalanceResponse
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.RpcNodeService
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.math.BigInteger

@Service
class BlockchainService(
    private val retrofitFactory: RetrofitFactory,
    private val relayClientService: RelayClientService,
    private val rpcNodeService: RpcNodeService,
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(BlockchainService::class.java)
    private val pusdContractAddress = PolymarketConstants.PUSD_CONTRACT_ADDRESS
    private val usdcContractAddress = PolymarketConstants.USDC_CONTRACT_ADDRESS
    private val safeProxyFactoryAddress = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"
    private val magicProxyFactoryAddress = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052"
    private val magicProxyInitCodeHash = "0xd21df8dc65880a8606f09fe0ce3df9b8869287ab0b058be05aa9e8af6330a00b"
    private val conditionalTokensAddress = PolymarketConstants.CTF_CONTRACT_ADDRESS
    private val wcolContractAddress = PolymarketConstants.NEG_RISK_WRAPPED_COLLATERAL_ADDRESS
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"
    private val computeProxyAddressFunctionSignature = "computeProxyAddress(address)"
    
    private val dataApi: PolymarketDataApi by lazy {
        val baseUrl = if (PolymarketConstants.DATA_API_BASE_URL.endsWith("/")) {
            PolymarketConstants.DATA_API_BASE_URL.dropLast(1)
        } else {
            PolymarketConstants.DATA_API_BASE_URL
        }
        val okHttpClient = createClient()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketDataApi::class.java)
    }
    
    private val polygonRpcApi: EthereumRpcApi by lazy {
        val rpcUrl = rpcNodeService.getHttpUrl()
        retrofitFactory.createEthereumRpcApi(rpcUrl)
    }
    
    suspend fun getProxyAddress(walletAddress: String, walletType: WalletType = WalletType.MAGIC): Result<String> {
        return try {
            when (walletType) {
                WalletType.SAFE -> {
                    val safeProxyResult = getSafeProxyAddress(walletAddress)
                    if (safeProxyResult.isSuccess) {
                        val safeProxyAddress = safeProxyResult.getOrNull()!!
                        logger.debug("Resolved Safe proxy address: {}", safeProxyAddress)
                        Result.success(safeProxyAddress)
                    } else {
                        Result.failure(safeProxyResult.exceptionOrNull() ?: Exception("Safe proxy lookup failed"))
                    }
                }
                WalletType.MAGIC -> {
                    val magicProxyAddress = calculateMagicProxyAddress(walletAddress)
                    logger.debug("Resolved Magic proxy address: {}", magicProxyAddress)
                    Result.success(magicProxyAddress)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to resolve proxy address: {}", e.message, e)
            Result.failure(e)
        }
    }

    /**
     *
     * address = keccak256(0xff ++ factory ++ salt ++ initCodeHash)[12:]
     * salt = keccak256(eoaAddress)
     *
     */
    fun calculateMagicProxyAddress(walletAddress: String): String {
        val eoaBytes = EthereumUtils.hexToBytes(walletAddress.lowercase())
        val salt = EthereumUtils.keccak256(eoaBytes)
        // data = 0xff ++ factory ++ salt ++ initCodeHash
        val prefix = byteArrayOf(0xff.toByte())
        val factoryBytes = EthereumUtils.hexToBytes(magicProxyFactoryAddress)
        val initCodeHashBytes = EthereumUtils.hexToBytes(magicProxyInitCodeHash)

        val data = prefix + factoryBytes + salt + initCodeHashBytes
        val hash = EthereumUtils.keccak256(data)
        return "0x" + hash.copyOfRange(12, 32).joinToString("") { "%02x".format(it) }
    }

    private suspend fun getSafeProxyAddress(walletAddress: String): Result<String> {
        return try {
            val rpcApi = polygonRpcApi
            val functionSelector = EthereumUtils.getFunctionSelector(computeProxyAddressFunctionSignature)
            val encodedAddress = EthereumUtils.encodeAddress(walletAddress)
            val data = functionSelector + encodedAddress
            val rpcRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to safeProxyFactoryAddress,
                        "data" to data
                    ),
                    "latest"
                )
            )
            val response = rpcApi.call(rpcRequest)

            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(Exception("RPC request failed: ${response.code()} ${response.message()}"))
            }

            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                return Result.failure(Exception("RPC error: ${rpcResponse.error.message}"))
            }
            val hexResult = rpcResponse.result?.asString
                ?: return Result.failure(Exception("RPC result is empty"))
            val proxyAddress = EthereumUtils.decodeAddress(hexResult)

            Result.success(proxyAddress)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun isContract(address: String): Boolean {
        return try {
            val rpcApi = polygonRpcApi

            val rpcRequest = JsonRpcRequest(
                method = "eth_getCode",
                params = listOf(address, "latest")
            )

            val response = rpcApi.call(rpcRequest)
            if (!response.isSuccessful || response.body() == null) {
                return false
            }

            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                return false
            }

            val code = rpcResponse.result?.asString ?: "0x"
            code != "0x" && code != "0x0"
        } catch (e: Exception) {
            logger.warn("Failed to check contract code: {}", e.message)
            false
        }
    }

    suspend fun isProxyDeployed(proxyAddress: String): Boolean {
        if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
            return false
        }
        return isContract(proxyAddress)
    }

    suspend fun getPusdAllowance(owner: String, spender: String): Result<BigInteger> {
        return try {
            if (owner.isBlank() || spender.isBlank()) {
                return Result.failure(IllegalArgumentException("owner and spender cannot be blank"))
            }
            val rpcRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to pusdContractAddress,
                        "data" to ("0xdd62ed3e" + EthereumUtils.encodeAddress(owner) + EthereumUtils.encodeAddress(spender))
                    ),
                    "latest"
                )
            )
            val response = polygonRpcApi.call(rpcRequest)
            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(Exception("RPC request failed: ${response.code()} ${response.message()}"))
            }
            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                return Result.failure(Exception("RPC error: ${rpcResponse.error.message}"))
            }
            val hexResult = rpcResponse.result?.asString
                ?: return Result.failure(Exception("RPC result is empty"))
            Result.success(EthereumUtils.decodeUint256(hexResult))
        } catch (e: Exception) {
            logger.warn("Failed to query pUSD allowance: {}", e.message)
            Result.failure(e)
        }
    }

    suspend fun getUsdcAllowance(owner: String, spender: String): Result<BigInteger> {
        return getPusdAllowance(owner, spender)
    }

    suspend fun isConditionalTokensApprovedForAll(owner: String, operator: String): Result<Boolean> {
        return try {
            if (owner.isBlank() || operator.isBlank()) {
                return Result.failure(IllegalArgumentException("owner and operator cannot be blank"))
            }
            val rpcRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to ("0xe985e9c5" + EthereumUtils.encodeAddress(owner) + EthereumUtils.encodeAddress(operator))
                    ),
                    "latest"
                )
            )
            val response = polygonRpcApi.call(rpcRequest)
            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(Exception("RPC request failed: ${response.code()} ${response.message()}"))
            }
            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                return Result.failure(Exception("RPC error: ${rpcResponse.error.message}"))
            }
            val hexResult = rpcResponse.result?.asString
                ?: return Result.failure(Exception("RPC result is empty"))
            Result.success(EthereumUtils.decodeUint256(hexResult) > BigInteger.ZERO)
        } catch (e: Exception) {
            logger.warn("Failed to query conditional tokens approval: {}", e.message)
            Result.failure(e)
        }
    }

    suspend fun getUsdcBalance(walletAddress: String, proxyAddress: String): Result<String> {
        return try {
            if (proxyAddress.isBlank()) {
                logger.error("proxyAddress cannot be blank")
                return Result.failure(IllegalArgumentException("proxyAddress cannot be blank"))
            }
            // V2 wallets may still hold legacy USDC.e alongside pUSD during migration.
            val tokenBalances = listOf(
                "pUSD" to pusdContractAddress,
                "USDC.e" to usdcContractAddress
            ).mapNotNull { (label, contractAddress) ->
                runCatching {
                    queryTokenBalanceViaRpc(contractAddress, proxyAddress).toSafeBigDecimal()
                }.onFailure { error ->
                    logger.warn("Failed to query {} balance: {}", label, error.message)
                }.getOrNull()
            }

            if (tokenBalances.isEmpty()) {
                return Result.failure(IllegalStateException("failed to query all collateral balances"))
            }

            val balance = tokenBalances.fold(BigDecimal.ZERO, BigDecimal::add).toPlainString()
            Result.success(balance)
        } catch (e: Exception) {
            logger.error("Failed to query USDC balance: {}", e.message, e)
            Result.failure(e)
        }
    }

    suspend fun getWalletBalance(walletAddress: String): Result<WalletBalanceResponse> {
        return try {
            if (walletAddress.isBlank()) {
                logger.error("walletAddress cannot be blank")
                return Result.failure(IllegalArgumentException("walletAddress cannot be blank"))
            }
            val positionsResult = getPositions(walletAddress)
            val positions = if (positionsResult.isSuccess) {
                positionsResult.getOrNull()?.filter { pos ->
                    val currentValue = pos.currentValue ?: 0.0
                    currentValue > 0
                }?.map { pos ->
                    PositionDto(
                        marketId = pos.conditionId ?: "",
                        title = pos.title,
                        side = pos.outcome ?: "",
                        quantity = pos.size?.toString() ?: "0",
                        avgPrice = pos.avgPrice?.toString() ?: "0",
                        currentValue = pos.currentValue?.toString() ?: "0",
                        pnl = pos.cashPnl?.toString()
                    )
                } ?: emptyList()
            } else {
                logger.warn("Failed to load positions list: {}", positionsResult.exceptionOrNull()?.message)
                emptyList()
            }
            val positionBalanceResult = getTotalValue(walletAddress)
            val positionBalance = if (positionBalanceResult.isSuccess) {
                positionBalanceResult.getOrNull() ?: "0"
            } else {
                logger.warn("Failed to query position value: {}", positionBalanceResult.exceptionOrNull()?.message)
                "0"
            }
            val availableBalanceResult = getUsdcBalance(
                walletAddress = walletAddress,
                proxyAddress = walletAddress
            )
            val availableBalance = if (availableBalanceResult.isSuccess) {
                availableBalanceResult.getOrNull() ?: throw Exception("Available balance is empty")
            } else {
                val error = availableBalanceResult.exceptionOrNull()
                logger.error("Failed to query available USDC balance: {}", error?.message)
                throw Exception("Available USDC balance query failed: ${error?.message}. Check the Ethereum RPC URL.")
            }
            val totalBalance = availableBalance.toSafeBigDecimal().add(positionBalance.toSafeBigDecimal())

            Result.success(
                WalletBalanceResponse(
                    availableBalance = availableBalance,
                    positionBalance = positionBalance,
                    totalBalance = totalBalance.toPlainString(),
                    positions = positions
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to load wallet balance: {}", e.message, e)
            Result.failure(e)
        }
    }
    
    private suspend fun queryTokenBalanceViaRpc(contractAddress: String, walletAddress: String): String {
        val rpcApi = polygonRpcApi
        // function signature: balanceOf(address) -> bytes4(0x70a08231)
        val functionSelector = "0x70a08231" // balanceOf(address)
        val paddedAddress = walletAddress.removePrefix("0x").lowercase().padStart(64, '0')
        val data = functionSelector + paddedAddress
        val rpcRequest = JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to contractAddress,
                    "data" to data
                ),
                "latest"
            )
        )
        val response = rpcApi.call(rpcRequest)
        
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("RPC request failed: ${response.code()} ${response.message()}")
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            throw Exception("RPC error: ${rpcResponse.error.message}")
        }
        val hexBalance = rpcResponse.result?.asString 
            ?: throw Exception("RPC result is empty")
        val balanceWei = BigInteger(hexBalance.removePrefix("0x"), 16)
        val balance = BigDecimal(balanceWei).divide(BigDecimal("1000000"))
        
        return balance.toPlainString()
    }
    
    suspend fun getPositions(proxyWalletAddress: String, sortBy: String? = "CURRENT"): Result<List<PositionResponse>> {
        return try {
            val response = dataApi.getPositions(
                user = proxyWalletAddress,
                limit = 500,
                offset = 0,
                sortBy = sortBy
            )
            
            if (response.isSuccessful && response.body() != null) {
                val positions = response.body()!!
                Result.success(positions)
            } else {
                val errorMsg = "Data API request failed: ${response.code()} ${response.message()}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logger.error("Failed to load positions: {}", e.message, e)
            Result.failure(e)
        }
    }
    
    /**
     * 1. getCollectionId(EMPTY_SET, conditionId, indexSet) -> collectionId
     * 2. getPositionId(collateralToken, collectionId) -> tokenId
     * 
     * - outcomeIndex = 0 -> indexSet = 1 (2^0)
     * - outcomeIndex = 1 -> indexSet = 2 (2^1)
     * - outcomeIndex = 2 -> indexSet = 4 (2^2)
     * 
     */
    suspend fun getTokenId(conditionId: String, outcomeIndex: Int): Result<String> {
        return try {
            val rpcApi = polygonRpcApi
            if (outcomeIndex < 0) {
                return Result.failure(IllegalArgumentException("outcomeIndex must be greater than or equal to 0"))
            }
            val indexSet = BigInteger.TWO.pow(outcomeIndex)
            val getCollectionIdSelector = EthereumUtils.getFunctionSelector("getCollectionId(bytes32,bytes32,uint256)")
            val encodedEmptySet = EthereumUtils.encodeBytes32(EMPTY_SET)
            val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
            val encodedIndexSet = EthereumUtils.encodeUint256(indexSet)
            val collectionIdData = getCollectionIdSelector + encodedEmptySet + encodedConditionId + encodedIndexSet
            
            val collectionIdRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to collectionIdData
                    ),
                    "latest"
                )
            )
            
            val collectionIdResponse = rpcApi.call(collectionIdRequest)
            if (!collectionIdResponse.isSuccessful || collectionIdResponse.body() == null) {
                return Result.failure(Exception("getCollectionId request failed: ${collectionIdResponse.code()} ${collectionIdResponse.message()}"))
            }
            
            val collectionIdResult = collectionIdResponse.body()!!
            if (collectionIdResult.error != null) {
                return Result.failure(Exception("getCollectionId RPC error: ${collectionIdResult.error}"))
            }
            val collectionId = collectionIdResult.result?.asString 
                ?: return Result.failure(Exception("getCollectionId returned empty result"))
            val getPositionIdSelector = EthereumUtils.getFunctionSelector("getPositionId(address,bytes32)")
            val encodedCollateral = EthereumUtils.encodeAddress(pusdContractAddress)
            val encodedCollectionId = EthereumUtils.encodeBytes32(collectionId)
            val positionIdData = getPositionIdSelector + encodedCollateral + encodedCollectionId
            
            val positionIdRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to positionIdData
                    ),
                    "latest"
                )
            )
            
            val positionIdResponse = rpcApi.call(positionIdRequest)
            if (!positionIdResponse.isSuccessful || positionIdResponse.body() == null) {
                return Result.failure(Exception("getPositionId request failed: ${positionIdResponse.code()} ${positionIdResponse.message()}"))
            }
            
            val positionIdResult = positionIdResponse.body()!!
            if (positionIdResult.error != null) {
                return Result.failure(Exception("getPositionId RPC error: ${positionIdResult.error}"))
            }
            val tokenId = positionIdResult.result?.asString 
                ?: return Result.failure(Exception("getPositionId returned empty result"))
            val tokenIdBigInt = EthereumUtils.decodeUint256(tokenId)
            
            Result.success(tokenIdBigInt.toString())
        } catch (e: Exception) {
            logger.error("Failed to resolve tokenId for conditionId={} outcomeIndex={}: {}", conditionId, outcomeIndex, e.message, e)
            Result.failure(e)
        }
    }
    
    @Deprecated("Use getTokenId(conditionId, outcomeIndex)", ReplaceWith("getTokenId(conditionId, outcomeIndex)"))
    suspend fun getTokenIdBySide(conditionId: String, side: String): Result<String> {
        logger.warn("getTokenIdBySide is deprecated, use getTokenId(conditionId, outcomeIndex): conditionId={}, side={}", conditionId, side)
        val outcomeIndex = when (side.uppercase()) {
            "YES" -> 0
            "NO" -> 1
            else -> return Result.failure(IllegalArgumentException("side must be YES or NO"))
        }
        return getTokenId(conditionId, outcomeIndex)
    }
    
    suspend fun getTotalValue(proxyWalletAddress: String): Result<String> {
        return try {
            val response = dataApi.getTotalValue(
                user = proxyWalletAddress,
                market = null
            )
            
            if (response.isSuccessful && response.body() != null) {
                val values = response.body()!!
                val totalValue = if (values.isNotEmpty()) {
                    values.first().value
                } else {
                    0.0
                }
                Result.success(totalValue.toString())
            } else {
                val errorMsg = "Data API request failed: ${response.code()} ${response.message()}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logger.error("Failed to calculate total value: {}", e.message, e)
            Result.failure(e)
        }
    }
    
    suspend fun redeemPositions(
        privateKey: String,
        proxyAddress: String,
        conditionId: String,
        indexSets: List<BigInteger>,
        isNegRisk: Boolean = false,
        walletType: WalletType = WalletType.SAFE,
        builderCredentials: RelayClientService.BuilderCredentials? = null
    ): Result<String> {
        return try {
            if (indexSets.isEmpty()) {
                return Result.failure(IllegalArgumentException("indexSets cannot be empty"))
            }
            if (conditionId.isBlank() || !conditionId.startsWith("0x") || conditionId.length != 66) {
                return Result.failure(IllegalArgumentException("conditionId must be a 66-character 0x-prefixed hex string"))
            }
            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress cannot be blank"))
            }

            val redeemTx = relayClientService.createRedeemTx(conditionId, indexSets, isNegRisk)
            relayClientService.execute(privateKey, proxyAddress, redeemTx, walletType, builderCredentials)
        } catch (e: Exception) {
            logger.error("Redeem positions failed: {}", e.message, e)
            Result.failure(e)
        }
    }

    suspend fun redeemPositionsBatch(
        privateKey: String,
        proxyAddress: String,
        redeemRequests: List<Triple<String, List<BigInteger>, Boolean>>,
        walletType: WalletType = WalletType.SAFE,
        builderCredentials: RelayClientService.BuilderCredentials? = null
    ): Result<String> {
        return try {
            if (redeemRequests.isEmpty()) {
                return Result.failure(IllegalArgumentException("redeemRequests cannot be empty"))
            }
            if (walletType == WalletType.MAGIC) {
                return Result.failure(IllegalArgumentException("Magic wallet does not support MultiSend"))
            }

            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress cannot be blank"))
            }
            for ((conditionId, _, _) in redeemRequests) {
                if (conditionId.isBlank() || !conditionId.startsWith("0x") || conditionId.length != 66) {
                    return Result.failure(IllegalArgumentException("Invalid conditionId in redeem batch: $conditionId"))
                }
            }
            val redeemTxs = redeemRequests.map { (conditionId, indexSets, isNegRisk) ->
                if (indexSets.isEmpty()) {
                    throw IllegalArgumentException("indexSets cannot be empty for conditionId=$conditionId")
                }
                relayClientService.createRedeemTx(conditionId, indexSets, isNegRisk)
            }
            val multiSendTx = relayClientService.createMultiSendTx(redeemTxs)

            logger.info("Created redeem batch with {} requests", redeemRequests.size)

            relayClientService.execute(privateKey, proxyAddress, multiSendTx, walletType, builderCredentials)
        } catch (e: Exception) {
            logger.error("Redeem batch failed: {}", e.message, e)
            Result.failure(e)
        }
    }

    suspend fun waitForTransactionConfirmed(
        txHash: String,
        maxWaitMs: Long = 120_000,
        pollIntervalMs: Long = 3_000
    ): Result<Unit> {
        val rpcApi = polygonRpcApi
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val req = JsonRpcRequest(method = "eth_getTransactionReceipt", params = listOf(txHash))
            val response = rpcApi.call(req)
            if (!response.isSuccessful || response.body() == null) {
                delay(pollIntervalMs)
                continue
            }
            val body = response.body()!!
            if (body.error != null) {
                delay(pollIntervalMs)
                continue
            }
            val result = body.result
            if (result == null || result.isJsonNull) {
                delay(pollIntervalMs)
                continue
            }
            val status = result.asJsonObject?.get("status")?.asString
            if (status == null) {
                delay(pollIntervalMs)
                continue
            }
            return when (status) {
                "0x1" -> Result.success(Unit)
                "0x0" -> Result.failure(Exception("Transaction reverted"))
                else -> Result.failure(Exception("Unexpected transaction status: $status"))
            }
        }
        return Result.failure(Exception("Transaction confirmation timed out after ${maxWaitMs}ms"))
    }

    suspend fun getWcolBalance(proxyAddress: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        val functionSelector = "0x70a08231" // balanceOf(address)
        val paddedAddress = proxyAddress.removePrefix("0x").lowercase().padStart(64, '0')
        val data = functionSelector + paddedAddress
        val rpcRequest = JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to wcolContractAddress,
                    "data" to data
                ),
                "latest"
            )
        )
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("WCOL balance request failed: ${response.code()} ${response.message()}"))
        }
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("WCOL balance RPC error: ${rpcResponse.error.message}"))
        }
        val hexBalance = rpcResponse.result?.asString ?: return Result.failure(Exception("WCOL balance result is empty"))
        val balance = EthereumUtils.decodeUint256(hexBalance)
        return Result.success(balance)
    }

    suspend fun unwrapWcolForProxy(
        privateKey: String,
        proxyAddress: String,
        walletType: WalletType,
        builderCredentials: RelayClientService.BuilderCredentials? = null
    ): Result<String?> {
        return try {
            val balanceResult = getWcolBalance(proxyAddress)
            val balance = balanceResult.getOrElse {
                logger.warn("Failed to query WCOL balance before unwrap: {}", it.message)
                return Result.success(null)
            }
            if (balance == BigInteger.ZERO) {
                return Result.success(null)
            }
            val unwrapTx = relayClientService.createUnwrapWcolTx(proxyAddress, balance)
            val executeResult = relayClientService.execute(privateKey, proxyAddress, unwrapTx, walletType, builderCredentials)
            executeResult.fold(
                onSuccess = { txHash ->
                    logger.info("WCOL unwrap submitted: proxy={}..., txHash={}", proxyAddress.take(10), txHash)
                    Result.success(txHash)
                },
                onFailure = { e ->
                    logger.error("WCOL unwrap execution failed: {}", e.message, e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            logger.error("Failed to unwrap WCOL: {}", e.message, e)
            Result.failure(e)
        }
    }

    private suspend fun getProxyNonce(proxyAddress: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        val nonceFunctionSelector = EthereumUtils.getFunctionSelector("nonce()")
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to proxyAddress,
                    "data" to nonceFunctionSelector
                ),
                "latest"
            )
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("Proxy nonce request failed: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("Proxy nonce RPC error: ${rpcResponse.error.message}"))
        }
        val hexNonce = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("Proxy nonce result is empty"))
        val nonce = EthereumUtils.decodeUint256(hexNonce)
        return Result.success(nonce)
    }
    
    private suspend fun getTransactionCount(address: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_getTransactionCount",
            params = listOf(address, "pending")
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("Nonce request failed: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("Nonce RPC error: ${rpcResponse.error.message}"))
        }
        val hexNonce = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("Nonce result is empty"))
        val nonce = EthereumUtils.decodeUint256(hexNonce)
        return Result.success(nonce)
    }
    
    private suspend fun getGasPrice(): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_gasPrice",
            params = emptyList()
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("Gas price request failed: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("Gas price RPC error: ${rpcResponse.error.message}"))
        }
        val hexGasPrice = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("Gas price result is empty"))
        val gasPrice = EthereumUtils.decodeUint256(hexGasPrice)
        return Result.success(gasPrice)
    }
    
    private fun buildTransaction(
        privateKey: String,
        from: String,
        to: String,
        data: String,
        nonce: BigInteger,
        gasLimit: BigInteger,
        gasPrice: BigInteger
    ): Map<String, Any> {
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val rawTransaction = org.web3j.crypto.RawTransaction.createTransaction(
            nonce,
            gasPrice,
            gasLimit,
            to,
            data
        )
        val chainId: Long = 137L
        val signedTransaction = org.web3j.crypto.TransactionEncoder.signMessage(rawTransaction, chainId, credentials)
        val hexValue = org.web3j.utils.Numeric.toHexString(signedTransaction)
        
        return mapOf(
            "from" to from,
            "to" to to,
            "data" to data,
            "nonce" to "0x${nonce.toString(16)}",
            "gas" to "0x${gasLimit.toString(16)}",
            "gasPrice" to "0x${gasPrice.toString(16)}",
            "value" to "0x0",
            "chainId" to "0x89",
            "rawTransaction" to hexValue
        )
    }
    
    private suspend fun sendTransaction(
        rpcApi: EthereumRpcApi,
        transaction: Map<String, Any>
    ): Result<String> {
        val rawTransaction = transaction["rawTransaction"] as? String
            ?: return Result.failure(IllegalArgumentException("rawTransaction is required"))
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_sendRawTransaction",
            params = listOf(rawTransaction)
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("Send transaction request failed: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("Send transaction RPC error: ${rpcResponse.error.message}"))
        }
        val txHash = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("transaction hash is empty"))
        return Result.success(txHash)
    }
    
    /**
     * 
     * @return Result<Pair<payoutDenominator, payouts>>
     */
    suspend fun getCondition(conditionId: String): Result<Pair<BigInteger, List<BigInteger>>> {
        return try {
            if (conditionId.isBlank() || !conditionId.startsWith("0x") || conditionId.length != 66) {
                return Result.failure(IllegalArgumentException("conditionId must be a 66-character 0x-prefixed hex string"))
            }
            
            val rpcApi = polygonRpcApi
            val getOutcomeSlotCountSelector = EthereumUtils.getFunctionSelector("getOutcomeSlotCount(bytes32)")
            val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
            val outcomeSlotCountData = getOutcomeSlotCountSelector + encodedConditionId
            
            val outcomeSlotCountRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to outcomeSlotCountData
                    ),
                    "latest"
                )
            )
            
            val outcomeSlotCountResponse = rpcApi.call(outcomeSlotCountRequest)
            
            if (!outcomeSlotCountResponse.isSuccessful || outcomeSlotCountResponse.body() == null) {
                return Result.failure(Exception("RPC request failed (getOutcomeSlotCount): ${outcomeSlotCountResponse.code()} ${outcomeSlotCountResponse.message()}"))
            }
            
            val outcomeSlotCountRpcResponse = outcomeSlotCountResponse.body()!!
            
            if (outcomeSlotCountRpcResponse.error != null) {
                val errorMsg = "RPC error (getOutcomeSlotCount, code=${outcomeSlotCountRpcResponse.error.code}): ${outcomeSlotCountRpcResponse.error.message}, data=${outcomeSlotCountRpcResponse.error.data}"
                logger.warn("Failed to query getOutcomeSlotCount for conditionId={}: {}", conditionId, errorMsg)
                logger.debug("RPC request context: to={}, data={}", conditionalTokensAddress, outcomeSlotCountData)
                return Result.failure(Exception(errorMsg))
            }
            
            val outcomeSlotCountHex = outcomeSlotCountRpcResponse.result?.asString 
                ?: return Result.failure(Exception("RPC result is empty"))
            
            val outcomeSlotCount = EthereumUtils.decodeUint256(outcomeSlotCountHex).toInt()
            if (outcomeSlotCount <= 0) {
                logger.debug("Condition has no outcomes yet: conditionId={}, outcomeSlotCount={}", conditionId, outcomeSlotCount)
                return Result.success(Pair(BigInteger.ZERO, emptyList()))
            }
            val payoutDenominatorSelector = EthereumUtils.getFunctionSelector("payoutDenominator(bytes32)")
            val payoutDenominatorData = payoutDenominatorSelector + encodedConditionId
            
            val payoutDenominatorRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to payoutDenominatorData
                    ),
                    "latest"
                )
            )
            
            val payoutDenominatorResponse = rpcApi.call(payoutDenominatorRequest)
            
            if (!payoutDenominatorResponse.isSuccessful || payoutDenominatorResponse.body() == null) {
                return Result.failure(Exception("RPC request failed (payoutDenominator): ${payoutDenominatorResponse.code()} ${payoutDenominatorResponse.message()}"))
            }
            
            val payoutDenominatorRpcResponse = payoutDenominatorResponse.body()!!
            
            if (payoutDenominatorRpcResponse.error != null) {
                val errorMsg = "RPC error (payoutDenominator, code=${payoutDenominatorRpcResponse.error.code}): ${payoutDenominatorRpcResponse.error.message}, data=${payoutDenominatorRpcResponse.error.data}"
                logger.warn("Failed to query payoutDenominator for conditionId={}: {}", conditionId, errorMsg)
                logger.debug("RPC request context: to={}, data={}", conditionalTokensAddress, payoutDenominatorData)
                return Result.failure(Exception(errorMsg))
            }
            
            val payoutDenominatorHex = payoutDenominatorRpcResponse.result?.asString 
                ?: return Result.failure(Exception("RPC result is empty"))
            
            val payoutDenominator = EthereumUtils.decodeUint256(payoutDenominatorHex)
            if (outcomeSlotCount <= 0) {
                logger.debug("Condition has no outcomes yet: conditionId={}, outcomeSlotCount={}", conditionId, outcomeSlotCount)
                return Result.success(Pair(BigInteger.ZERO, emptyList()))
            }
            if (payoutDenominator == BigInteger.ZERO) {
                logger.debug("Condition payout denominator is zero: conditionId={}, payoutDenominator={}", conditionId, payoutDenominator)
                return Result.success(Pair(BigInteger.ZERO, emptyList()))
            }
            val payouts = mutableListOf<BigInteger>()
            for (i in 0 until outcomeSlotCount) {
                val payoutNumeratorsFunctionSelector = EthereumUtils.getFunctionSelector("payoutNumerators(bytes32,uint256)")
                val encodedIndex = EthereumUtils.encodeUint256(BigInteger.valueOf(i.toLong()))
                val payoutNumeratorsData = payoutNumeratorsFunctionSelector + encodedConditionId + encodedIndex
                
                val payoutRequest = JsonRpcRequest(
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to conditionalTokensAddress,
                            "data" to payoutNumeratorsData
                        ),
                        "latest"
                    )
                )
                
                val payoutResponse = rpcApi.call(payoutRequest)
                if (!payoutResponse.isSuccessful || payoutResponse.body() == null) {
                    logger.warn("payoutNumerators request failed: index={}", i)
                    continue
                }
                
                val payoutRpcResponse = payoutResponse.body()!!
                if (payoutRpcResponse.error != null) {
                    logger.warn("payoutNumerators RPC error: index={}, error={}", i, payoutRpcResponse.error.message)
                    continue
                }
                
                val payoutHex = payoutRpcResponse.result?.asString ?: "0x0"
                val payout = EthereumUtils.decodeUint256(payoutHex)
                payouts.add(payout)
            }
            
            Result.success(Pair(payoutDenominator, payouts))
        } catch (e: Exception) {
            logger.error("Failed to query condition details for conditionId={}: {}", conditionId, e.message, e)
            Result.failure(e)
        }
    }
    
    suspend fun getTransactionDetails(txHash: String): Result<String> {
        return try {
            val rpcApi = polygonRpcApi
            val txRequest = JsonRpcRequest(
                method = "eth_getTransactionByHash",
                params = listOf(txHash)
            )
            
            val txResponse = rpcApi.call(txRequest)
            if (!txResponse.isSuccessful || txResponse.body() == null) {
                return Result.failure(Exception("Transaction details request failed: ${txResponse.code()} ${txResponse.message()}"))
            }
            
            val txRpcResponse = txResponse.body()!!
            if (txRpcResponse.error != null) {
                return Result.failure(Exception("Transaction details RPC error: ${txRpcResponse.error.message}"))
            }
            val txResult = txRpcResponse.result?.toString() 
                ?: return Result.failure(Exception("transaction hash is empty"))
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )
            
            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                return Result.success("Transaction details:\n$txResult\n\nTransaction receipt is not available yet.")
            }
            
            val receiptRpcResponse = receiptResponse.body()!!
            val receiptResult = if (receiptRpcResponse.error != null) {
                "Transaction receipt error: ${receiptRpcResponse.error.message}"
            } else {
                receiptRpcResponse.result?.toString() ?: "Transaction receipt is empty"
            }
            
            Result.success("Transaction details:\n$txResult\n\nTransaction receipt:\n$receiptResult")
        } catch (e: Exception) {
            logger.error("Failed to load transaction details: {}", e.message, e)
            Result.failure(e)
        }
    }
}
