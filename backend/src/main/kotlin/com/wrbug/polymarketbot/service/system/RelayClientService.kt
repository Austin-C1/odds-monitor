package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.Eip712Encoder
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import retrofit2.Response
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

@Service
class RelayClientService(
    private val retrofitFactory: RetrofitFactory,
    private val accountRepository: AccountRepository,
    private val cryptoUtils: CryptoUtils,
    private val systemConfigService: SystemConfigService,
    private val rpcNodeService: RpcNodeService
) {

    private val logger = LoggerFactory.getLogger(RelayClientService::class.java)
    private val conditionalTokensAddress = PolymarketConstants.CTF_CONTRACT_ADDRESS
    private val usdcContractAddress = PolymarketConstants.PUSD_CONTRACT_ADDRESS
    private val negRiskAdapterAddress = PolymarketConstants.NEG_RISK_ADAPTER_ADDRESS
    private val negRiskWrappedCollateralAddress = PolymarketConstants.NEG_RISK_WRAPPED_COLLATERAL_ADDRESS
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"
    private val proxyFactoryAddress = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052"
    private val relayHubAddress = "0xD216153c06E857cD7f72665E0aF1d7D82172F494"
    private val defaultProxyGasLimit = "10000000"
    private val safeMultisendAddress = "0xA238CBeb142c10Ef7Ad8442C6D1f9E89e07e7761"
    private val RELAYER_TYPE_PROXY = "PROXY"
    private val RELAYER_TYPE_SAFE = "SAFE"
    private val RELAYER_TYPE_SAFE_CREATE = "SAFE-CREATE"
    private val safeProxyFactoryAddress = PolymarketConstants.SAFE_PROXY_FACTORY_ADDRESS

    private val polygonRpcApi: EthereumRpcApi by lazy {
        val rpcUrl = rpcNodeService.getHttpUrl()
        retrofitFactory.createEthereumRpcApi(rpcUrl)
    }

    private val builderRelayerRateLimitMaxAttempts = 3

    private val builderRelayerRateLimitBackoffMs = 2000L

    private val builderRelayerQuotaBlockedUntilMs = AtomicLong(0)

    fun isBuilderRelayerQuotaBlocked(): Boolean = System.currentTimeMillis() < builderRelayerQuotaBlockedUntilMs.get()

    fun getBuilderRelayerQuotaBlockedRemainingSeconds(): Long {
        val remaining = (builderRelayerQuotaBlockedUntilMs.get() - System.currentTimeMillis()) / 1000
        return maxOf(0, remaining)
    }

    private fun createNegRiskRedeemTx(conditionId: String, amounts: List<BigInteger>): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("redeemPositions(bytes32,uint256[])")
        val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
        val arrayOffset = BigInteger.valueOf(64)
        val arrayLength = BigInteger.valueOf(amounts.size.toLong())
        val encodedArrayOffset = EthereumUtils.encodeUint256(arrayOffset)
        val encodedArrayLength = EthereumUtils.encodeUint256(arrayLength)
        val encodedArrayElements = amounts.joinToString("") { EthereumUtils.encodeUint256(it) }
        val callData = "0x" + functionSelector.removePrefix("0x") +
                encodedConditionId +
                encodedArrayOffset +
                encodedArrayLength +
                encodedArrayElements

        return SafeTransaction(
            to = negRiskAdapterAddress,
            operation = 0,
            data = callData,
            value = "0"
        )
    }

    private fun updateQuotaBlockedFromErrorBody(errorBody: String) {
        if (!errorBody.contains("quota exceeded", ignoreCase = true)) return
        val regex = Regex("resets\\s+in\\s+(\\d+)\\s+seconds", RegexOption.IGNORE_CASE)
        regex.find(errorBody)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { seconds ->
            val untilMs = System.currentTimeMillis() + seconds * 1000
            builderRelayerQuotaBlockedUntilMs.set(untilMs)
            logger.warn("Builder Relayer quota exceeded; blocked for {} seconds", seconds)
        }
    }

    private suspend fun <T> withBuilderRelayerRateLimitRetry(block: suspend () -> Response<T>): Response<T> {
        var lastResponse: Response<T>? = null
        for (attempt in 1..builderRelayerRateLimitMaxAttempts) {
            val response = block()
            lastResponse = response
            if (response.code() != 429) return response
            if (attempt == builderRelayerRateLimitMaxAttempts) return response
            val delayMs = builderRelayerRateLimitBackoffMs * (1L shl (attempt - 1))
            logger.warn("Builder Relayer API returned 429, retrying in {}ms ({}/{})", delayMs, attempt, builderRelayerRateLimitMaxAttempts)
            delay(delayMs)
        }
        return lastResponse!!
    }

    private fun getBuilderRelayerApi(builderCredentials: BuilderCredentials): BuilderRelayerApi {
        return retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderCredentials.apiKey,
            secret = builderCredentials.secret,
            passphrase = builderCredentials.passphrase
        )
    }

    private fun isBuilderRelayerEnabled(builderCredentials: BuilderCredentials?): Boolean {
        return PolymarketConstants.BUILDER_RELAYER_URL.isNotBlank() &&
                builderCredentials != null &&
                builderCredentials.apiKey.isNotBlank() &&
                builderCredentials.secret.isNotBlank() &&
                builderCredentials.passphrase.isNotBlank()
    }

    private fun getAnyConfiguredBuilderCredentials(): BuilderCredentials? {
        val account = accountRepository.findFirstByBuilderApiKeyIsNotNullAndBuilderSecretIsNotNullAndBuilderPassphraseIsNotNullOrderByCreatedAtAsc()
            ?: return null

        return try {
            BuilderCredentials(
                apiKey = cryptoUtils.decrypt(account.builderApiKey!!),
                secret = cryptoUtils.decrypt(account.builderSecret!!),
                passphrase = cryptoUtils.decrypt(account.builderPassphrase!!)
            )
        } catch (e: Exception) {
            logger.error("Failed to load Builder credentials for accountId={}", account.id, e)
            null
        }
    }

    fun isBuilderApiKeyConfigured(): Boolean {
        return accountRepository.countByBuilderApiKeyIsNotNullAndBuilderSecretIsNotNullAndBuilderPassphraseIsNotNull() > 0
    }

    suspend fun checkBuilderRelayerApiHealth(): Result<Long> {
        return try {
            val builderCredentials = getAnyConfiguredBuilderCredentials()
                ?: return Result.failure(IllegalStateException("Builder credentials are not configured"))

            val relayerApi = getBuilderRelayerApi(builderCredentials)
            val testAddress = "0x0000000000000000000000000000000000000000"
            val startTime = System.currentTimeMillis()
            val response = relayerApi.getDeployed(testAddress)
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                Result.success(responseTime)
            } else {
                val errorBody = response.errorBody()?.string() ?: "unknown error"
                updateQuotaBlockedFromErrorBody(errorBody)
                Result.failure(Exception("Builder Relayer API health check failed: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            logger.error("Builder Relayer API health check failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    data class BuilderCredentials(
        val apiKey: String,
        val secret: String,
        val passphrase: String
    )
    data class RedeemParams(
        val conditionId: String,
        val outcomeIndex: Int
    )

    data class SafeTransaction(
        val to: String,
        val operation: Int = 0,       // 0 = CALL, 1 = DELEGATE_CALL
        val data: String,
        val value: String = "0"
    )

    fun createRedeemTx(params: RedeemParams): SafeTransaction {
        val (conditionId, outcomeIndex) = params
        val indexSet = BigInteger.TWO.pow(outcomeIndex)
        return createRedeemTx(conditionId, listOf(indexSet))
    }

    fun createRedeemTx(conditionId: String, indexSets: List<BigInteger>, isNegRisk: Boolean = false): SafeTransaction {
        if (isNegRisk) {
            return createNegRiskRedeemTx(conditionId, indexSets)
        }
        val functionSelector = EthereumUtils.getFunctionSelector(
            "redeemPositions(address,bytes32,bytes32,uint256[])"
        )
        val collateralAddress = usdcContractAddress
        val encodedCollateral = EthereumUtils.encodeAddress(collateralAddress)
        val encodedParentCollection = EthereumUtils.encodeBytes32(EMPTY_SET)
        val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
        val arrayOffset = BigInteger.valueOf(128)
        val arrayLength = BigInteger.valueOf(indexSets.size.toLong())
        val encodedArrayOffset = EthereumUtils.encodeUint256(arrayOffset)
        val encodedArrayLength = EthereumUtils.encodeUint256(arrayLength)
        val encodedArrayElements = indexSets.joinToString("") { EthereumUtils.encodeUint256(it) }
        val callData = "0x" + functionSelector.removePrefix("0x") +
                encodedCollateral +
                encodedParentCollection +
                encodedConditionId +
                encodedArrayOffset +
                encodedArrayLength +
                encodedArrayElements

        return SafeTransaction(
            to = conditionalTokensAddress,
            operation = 0,  // CALL
            data = callData,
            value = "0"
        )
    }

    fun createUnwrapWcolTx(toAddress: String, amountWei: BigInteger): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("unwrap(address,uint256)")
        val encodedTo = EthereumUtils.encodeAddress(toAddress)
        val encodedAmount = EthereumUtils.encodeUint256(amountWei)
        val callData = "0x" + functionSelector.removePrefix("0x") + encodedTo + encodedAmount
        return SafeTransaction(
            to = negRiskWrappedCollateralAddress,
            operation = 0,  // CALL
            data = callData,
            value = "0"
        )
    }

    fun createPusdApproveTx(spender: String, amount: BigInteger): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("approve(address,uint256)")
        val encodedSpender = EthereumUtils.encodeAddress(spender)
        val encodedAmount = EthereumUtils.encodeUint256(amount)
        val callData = "0x" + functionSelector.removePrefix("0x") + encodedSpender + encodedAmount
        return SafeTransaction(
            to = usdcContractAddress,
            operation = 0,
            data = callData,
            value = "0"
        )
    }

    fun createUsdcApproveTx(spender: String, amount: BigInteger): SafeTransaction {
        return createPusdApproveTx(spender, amount)
    }

    fun createConditionalTokensApprovalForAllTx(operator: String, approved: Boolean): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("setApprovalForAll(address,bool)")
        val encodedOperator = EthereumUtils.encodeAddress(operator)
        val encodedApproved = EthereumUtils.encodeUint256(if (approved) BigInteger.ONE else BigInteger.ZERO)
        val callData = "0x" + functionSelector.removePrefix("0x") + encodedOperator + encodedApproved
        return SafeTransaction(
            to = conditionalTokensAddress,
            operation = 0,
            data = callData,
            value = "0"
        )
    }

    fun createMultiSendTx(safeTxs: List<SafeTransaction>): SafeTransaction {
        if (safeTxs.isEmpty()) {
            throw IllegalArgumentException("safeTxs cannot be empty")
        }
        if (safeTxs.size == 1) {
            logger.debug("Single safe transaction received, skipping MultiSend wrapping")
            return safeTxs.first()
        }

        logger.debug("Packing {} safe transactions into MultiSend", safeTxs.size)
        val multiSendSelector = EthereumUtils.getFunctionSelector("multiSend(bytes)")
        val encodedTransactions = safeTxs.map { tx ->
            val operation = tx.operation.toByte()
            val toHex = tx.to.removePrefix("0x").lowercase().padStart(40, '0').takeLast(40)
            val to = EthereumUtils.hexToBytes(toHex)
            val valueHex = BigInteger(tx.value).toString(16).padStart(64, '0')
            val value = EthereumUtils.hexToBytes(valueHex)

            val dataBytes = EthereumUtils.hexToBytes(tx.data.removePrefix("0x"))
            val dataLengthHex = BigInteger.valueOf(dataBytes.size.toLong()).toString(16).padStart(64, '0')
            val dataLength = EthereumUtils.hexToBytes(dataLengthHex)

            // encodePacked: operation(1) + to(20) + value(32) + dataLength(32) + data(variable)
            byteArrayOf(operation) + to + value + dataLength + dataBytes
        }
        val concatenatedTransactions = encodedTransactions.reduce { acc, bytes -> acc + bytes }
        val totalDataLength = concatenatedTransactions.size
        val paddedLength = ((totalDataLength + 31) / 32) * 32
        val paddedData = concatenatedTransactions + ByteArray(paddedLength - totalDataLength)

        val encodedOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        val encodedLength = EthereumUtils.encodeUint256(BigInteger.valueOf(totalDataLength.toLong()))
        val encodedData = paddedData.joinToString("") { "%02x".format(it) }

        val callData = "0x" + multiSendSelector.removePrefix("0x") + encodedOffset + encodedLength + encodedData

        return SafeTransaction(
            to = safeMultisendAddress,
            operation = 1,  // DelegateCall
            data = callData,
            value = "0"
        )
    }

    suspend fun execute(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        walletType: WalletType = WalletType.SAFE,
        builderCredentials: BuilderCredentials? = null
    ): Result<String> {
        return try {
            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress cannot be blank"))
            }

            if (walletType == WalletType.MAGIC) {
                if (!isBuilderRelayerEnabled(builderCredentials)) {
                    return Result.failure(IllegalStateException("Magic wallet requires Builder Relayer credentials"))
                }
                if (isBuilderRelayerQuotaBlocked()) {
                    val remaining = getBuilderRelayerQuotaBlockedRemainingSeconds()
                    return Result.failure(IllegalStateException("Builder Relayer quota is temporarily blocked for ${remaining}s"))
                }
                logger.info("Using account Builder Relayer for Magic account")
                return executeViaBuilderRelayerProxy(
                    privateKey,
                    proxyAddress,
                    safeTx,
                    builderCredentials!!.apiKey,
                    builderCredentials.secret,
                    builderCredentials.passphrase
                )
            }

            if (isBuilderRelayerEnabled(builderCredentials) && !isBuilderRelayerQuotaBlocked()) {
                logger.info("Using account Builder Relayer for Safe account")
                return executeViaBuilderRelayer(
                    privateKey,
                    proxyAddress,
                    safeTx,
                    builderCredentials!!.apiKey,
                    builderCredentials.secret,
                    builderCredentials.passphrase
                )
            }

            if (isBuilderRelayerQuotaBlocked()) {
                logger.info("Builder Relayer quota blocked, fallback to manual Safe execution")
            } else {
                logger.info("No Builder configured for Safe account, fallback to manual execution")
            }
            return executeManually(privateKey, proxyAddress, safeTx)
        } catch (e: Exception) {
            logger.error("Transaction execution failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    private suspend fun executeViaBuilderRelayerProxy(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )

        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val fromAddress = credentials.address

        val relayPayloadResponse = withBuilderRelayerRateLimitRetry { relayerApi.getRelayPayload(fromAddress, RELAYER_TYPE_PROXY) }
        if (!relayPayloadResponse.isSuccessful || relayPayloadResponse.body() == null) {
            val errorBody = relayPayloadResponse.errorBody()?.string() ?: "response body is empty"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("Relay payload request failed: code={}, body={}", relayPayloadResponse.code(), errorBody)
            return Result.failure(Exception("Relay payload request failed: ${relayPayloadResponse.code()} - $errorBody"))
        }
        val relayPayload = relayPayloadResponse.body()!!
        val relayAddress = relayPayload.address
        val nonce = relayPayload.nonce

        val proxyCallData = encodeProxyTransactionData(safeTx)
        val gasLimit = try {
            estimateProxyGasLimit(fromAddress, proxyFactoryAddress, proxyCallData)
        } catch (e: Exception) {
            logger.warn("Failed to estimate proxy gas limit, fallback to default gas limit: {}", e.message, e)
            defaultProxyGasLimit
        }

        val structHash = createProxyStructHash(
            from = fromAddress,
            to = proxyFactoryAddress,
            data = proxyCallData,
            txFee = "0",
            gasPrice = "0",
            gasLimit = gasLimit,
            nonce = nonce,
            relayHubAddress = relayHubAddress,
            relayAddress = relayAddress
        )

        val prefix = "\u0019Ethereum Signed Message:\n32".toByteArray(Charsets.UTF_8)
        val messageWithPrefix = ByteArray(prefix.size + structHash.size)
        System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
        System.arraycopy(structHash, 0, messageWithPrefix, prefix.size, structHash.size)

        val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
        keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
        val hashWithPrefix = ByteArray(keccak256.digestSize)
        keccak256.doFinal(hashWithPrefix, 0)

        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val signature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)
        val sigHex = "0x" + org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0') +
                org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0') +
                String.format("%02x", (signature.v as ByteArray).getOrElse(0) { 0 }.toInt() and 0xff)

        val request = BuilderRelayerApi.TransactionRequest(
            type = RELAYER_TYPE_PROXY,
            from = fromAddress,
            to = proxyFactoryAddress,
            proxyWallet = proxyAddress,
            data = proxyCallData,
            nonce = nonce,
            signature = sigHex,
            signatureParams = BuilderRelayerApi.SignatureParams(
                gasPrice = "0",
                gasLimit = gasLimit,
                relayerFee = "0",
                relayHub = relayHubAddress,
                relay = relayAddress
            ),
            metadata = "Redeem positions via Builder Relayer PROXY"
        )

        val response = withBuilderRelayerRateLimitRetry { relayerApi.submitTransaction(request) }
        if (!response.isSuccessful || response.body() == null) {
            val errorBody = response.errorBody()?.string() ?: "response body is empty"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("Builder Relayer PROXY request failed: code={}, body={}", response.code(), errorBody)
            return Result.failure(Exception("Builder Relayer PROXY request failed: ${response.code()} - $errorBody"))
        }

        val relayerResponse = response.body()!!
        val txHash = relayerResponse.transactionHash ?: relayerResponse.hash
            ?: return Result.failure(Exception("Builder Relayer did not return a transaction hash"))
        logger.info("Builder Relayer PROXY submission succeeded: transactionID={}, txHash={}", relayerResponse.transactionID, txHash)
        return Result.success(txHash)
    }

    /**
     * 
     * - selector (4 bytes)
     * - array offset (32 bytes) = 32
     * - array length (32 bytes) = 1
     *   - typeCode (32 bytes) = 1
     *   - to (32 bytes)
     *   - value (32 bytes) = 0
     *   - data length (32 bytes)
     *   - data (padded to 32-byte boundary)
     */
    private fun encodeProxyTransactionData(safeTx: SafeTransaction): String {
        val selector = EthereumUtils.getFunctionSelector("proxy((uint8,address,uint256,bytes)[])")
        val callData = safeTx.data.removePrefix("0x")
        val dataLen = callData.length / 2
        val dataLenPadded = (dataLen + 31) / 32 * 32 * 2
        val dataPadded = callData.padEnd(dataLenPadded, '0')
        val arrayOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        // 2. array length: 1
        val arrayLength = EthereumUtils.encodeUint256(BigInteger.ONE)
        val tupleOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        //    - typeCode: 1
        val typeCode = EthereumUtils.encodeUint256(BigInteger.ONE)
        //    - to: address
        val toEncoded = EthereumUtils.encodeAddress(safeTx.to)
        //    - value: 0
        val valueEncoded = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val dataOffsetInTuple = BigInteger.valueOf(128)
        val dataOffsetEncoded = EthereumUtils.encodeUint256(dataOffsetInTuple)
        //    - data length
        val dataLengthEncoded = EthereumUtils.encodeUint256(BigInteger.valueOf(dataLen.toLong()))
        //    - data (padded)
        
        return "0x" + selector.removePrefix("0x") + arrayOffset + arrayLength +
                tupleOffset + typeCode + toEncoded + valueEncoded + dataOffsetEncoded +
                dataLengthEncoded + dataPadded
    }

    private suspend fun estimateProxyGasLimit(
        from: String,
        to: String,
        data: String
    ): String {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_estimateGas",
            params = listOf(
                mapOf(
                    "from" to from,
                    "to" to to,
                    "data" to data
                )
            )
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("eth_estimateGas request failed: ${response.code()} ${response.message()}")
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            throw Exception("eth_estimateGas RPC error: ${rpcResponse.error.message}")
        }
        
        val hexGasLimit = rpcResponse.result?.asString
            ?: throw Exception("eth_estimateGas result is empty")
        val gasLimitBigInt = BigInteger(hexGasLimit.removePrefix("0x"), 16)
        return gasLimitBigInt.toString()
    }

    /**
     * concat: "rlx:" + from + to + data + txFee + gasPrice + gasLimit + nonce + relayHub + relay, then keccak256
     */
    private fun createProxyStructHash(
        from: String,
        to: String,
        data: String,
        txFee: String,
        gasPrice: String,
        gasLimit: String,
        nonce: String,
        relayHubAddress: String,
        relayAddress: String
    ): ByteArray {
        val rlxPrefix = "rlx:".toByteArray(Charsets.UTF_8)
        val fromBytes = EthereumUtils.hexToBytes(from.lowercase().removePrefix("0x").padStart(40, '0'))
        val toBytes = EthereumUtils.hexToBytes(to.lowercase().removePrefix("0x").padStart(40, '0'))
        val dataBytes = EthereumUtils.hexToBytes(data.removePrefix("0x"))
        val txFeeBytes = EthereumUtils.encodeUint256(BigInteger(txFee)).let { EthereumUtils.hexToBytes(it) }
        val gasPriceBytes = EthereumUtils.encodeUint256(BigInteger(gasPrice)).let { EthereumUtils.hexToBytes(it) }
        val gasLimitBytes = EthereumUtils.encodeUint256(BigInteger(gasLimit)).let { EthereumUtils.hexToBytes(it) }
        val nonceBytes = EthereumUtils.encodeUint256(BigInteger(nonce)).let { EthereumUtils.hexToBytes(it) }
        val relayHubBytes = EthereumUtils.hexToBytes(relayHubAddress.lowercase().removePrefix("0x").padStart(40, '0'))
        val relayBytes = EthereumUtils.hexToBytes(relayAddress.lowercase().removePrefix("0x").padStart(40, '0'))

        val concat = rlxPrefix + fromBytes + toBytes + dataBytes + txFeeBytes + gasPriceBytes +
                gasLimitBytes + nonceBytes + relayHubBytes + relayBytes
        return EthereumUtils.keccak256(concat)
    }

    private suspend fun executeViaBuilderRelayer(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val fromAddress = credentials.address
        val redeemCallData = safeTx.data
        val nonceResponse = withBuilderRelayerRateLimitRetry { relayerApi.getNonce(fromAddress, RELAYER_TYPE_SAFE) }
        if (!nonceResponse.isSuccessful || nonceResponse.body() == null) {
            val errorBody = nonceResponse.errorBody()?.string() ?: "response body is empty"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("Builder Relayer nonce request failed: code={}, body={}", nonceResponse.code(), errorBody)
            return Result.failure(Exception("Builder Relayer nonce request failed: ${nonceResponse.code()} - $errorBody"))
        }
        val proxyNonce = BigInteger(nonceResponse.body()!!.nonce)
        logger.debug(
            "Safe exec context: nonce={}, to={}, value={}, dataLen={}, operation={}, proxyWallet={}",
            proxyNonce,
            safeTx.to,
            safeTx.value,
            redeemCallData.removePrefix("0x").length / 2,
            safeTx.operation,
            proxyAddress
        )
        val safeTxGas = BigInteger.ZERO
        val baseGas = BigInteger.ZERO
        val safeGasPrice = BigInteger.ZERO
        val gasToken = "0x0000000000000000000000000000000000000000"
        val refundReceiver = "0x0000000000000000000000000000000000000000"

        val safeDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeDomain(
            chainId = 137L,
            verifyingContract = proxyAddress
        )

        val safeTxHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeTx(
            to = safeTx.to,
            value = BigInteger.ZERO,
            data = redeemCallData,
            operation = safeTx.operation,
            safeTxGas = safeTxGas,
            baseGas = baseGas,
            gasPrice = safeGasPrice,
            gasToken = gasToken,
            refundReceiver = refundReceiver,
            nonce = proxyNonce
        )

        val safeTxStructuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
            domainSeparator = safeDomainSeparator,
            messageHash = safeTxHash
        )
        logger.debug(
            "Safe exec structHash=0x{}, hashToSign source=keccak256(prefix + structHash)",
            safeTxStructuredHash.joinToString("") { "%02x".format(it) }
        )
        val prefix = "\u0019Ethereum Signed Message:\n${safeTxStructuredHash.size}".toByteArray(Charsets.UTF_8)
        val messageWithPrefix = ByteArray(prefix.size + safeTxStructuredHash.size)
        System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
        System.arraycopy(safeTxStructuredHash, 0, messageWithPrefix, prefix.size, safeTxStructuredHash.size)
        val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
        keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
        val hashWithPrefix = ByteArray(keccak256.digestSize)
        keccak256.doFinal(hashWithPrefix, 0)

        logger.debug(
            "Safe exec hashToSign=0x{} (personal_sign payload, 32 bytes)",
            hashWithPrefix.joinToString("") { "%02x".format(it) }
        )

        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val safeSignature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)
        val packedSignature = splitAndPackSig(safeSignature)
        val request = BuilderRelayerApi.TransactionRequest(
            type = RELAYER_TYPE_SAFE,
            from = fromAddress,
            to = safeTx.to,
            proxyWallet = proxyAddress,
            data = redeemCallData,
            nonce = proxyNonce.toString(),
            signature = packedSignature,
            signatureParams = BuilderRelayerApi.SignatureParams(
                gasPrice = "0",
                operation = safeTx.operation.toString(),
                safeTxnGas = "0",
                baseGas = "0",
                gasToken = gasToken,
                refundReceiver = refundReceiver
            ),
            metadata = if (safeTx.operation == 1) {
                "MultiSend redeem positions via Builder Relayer"
            } else {
                "Redeem positions via Builder Relayer"
            }
        )
        val response = withBuilderRelayerRateLimitRetry { relayerApi.submitTransaction(request) }

        if (!response.isSuccessful || response.body() == null) {
            val errorBody = response.errorBody()?.string() ?: "response body is empty"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("Builder Relayer request failed: code={}, body={}", response.code(), errorBody)
            return Result.failure(Exception("Builder Relayer request failed: ${response.code()} - $errorBody"))
        }

        val relayerResponse = response.body()!!
        val txHash = relayerResponse.transactionHash ?: relayerResponse.hash
        ?: return Result.failure(Exception("Builder Relayer did not return a transaction hash"))

        logger.info("Builder Relayer submission succeeded: transactionID={}, txHash={}", relayerResponse.transactionID, txHash)
        return Result.success(txHash)
    }

    suspend fun deploySafeViaBuilderRelayer(
        privateKey: String,
        proxyAddress: String,
        fromAddress: String,
        builderCredentials: BuilderCredentials? = null
    ): Result<String> {
        return try {
            if (!isBuilderRelayerEnabled(builderCredentials)) {
                return Result.failure(IllegalStateException("Builder credentials are required for Safe relay"))
            }
            if (isBuilderRelayerQuotaBlocked()) {
                val remaining = getBuilderRelayerQuotaBlockedRemainingSeconds()
                return Result.failure(IllegalStateException("Builder Relayer quota is temporarily blocked for ${remaining}s"))
            }
            val relayerApi = getBuilderRelayerApi(builderCredentials!!)
            val zeroAddress = "0x0000000000000000000000000000000000000000"
            val paymentToken = zeroAddress
            val payment = "0"
            val paymentReceiver = zeroAddress
            val domainSeparator = Eip712Encoder.encodeSafeCreateDomain(
                name = PolymarketConstants.SAFE_FACTORY_EIP712_NAME,
                chainId = 137L,
                verifyingContract = safeProxyFactoryAddress
            )
            val createProxyHash = Eip712Encoder.encodeCreateProxyMessage(
                paymentToken = paymentToken,
                payment = BigInteger.ZERO,
                paymentReceiver = paymentReceiver
            )
            val digest = Eip712Encoder.hashStructuredData(domainSeparator, createProxyHash)
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
            val signature = org.web3j.crypto.Sign.signMessage(digest, ecKeyPair, false)
            val signatureHex = signatureToStandardHex(signature)
            val request = BuilderRelayerApi.TransactionRequest(
                type = RELAYER_TYPE_SAFE_CREATE,
                from = fromAddress,
                to = safeProxyFactoryAddress,
                proxyWallet = proxyAddress,
                data = "0x",
                nonce = null,
                signature = signatureHex,
                signatureParams = BuilderRelayerApi.SignatureParams(
                    paymentToken = paymentToken,
                    payment = payment,
                    paymentReceiver = paymentReceiver
                ),
                metadata = null
            )
            val response = withBuilderRelayerRateLimitRetry { relayerApi.submitTransaction(request) }
            if (!response.isSuccessful || response.body() == null) {
                val errorBody = response.errorBody()?.string() ?: "unknown error"
                updateQuotaBlockedFromErrorBody(errorBody)
                logger.error("Builder Relayer SAFE-CREATE failed: code=${response.code()}, body=$errorBody")
                return Result.failure(Exception("Safe deployment failed: ${response.code()} - $errorBody"))
            }
            val relayerResponse = response.body()!!
            val txHash = relayerResponse.transactionHash ?: relayerResponse.hash
                ?: return Result.failure(Exception("Builder Relayer response missing transaction hash"))
            logger.info("Safe deployed successfully: proxy=$proxyAddress, txHash=$txHash")
            Result.success(txHash)
        } catch (e: Exception) {
            logger.error("Safe deployment failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun signatureToStandardHex(signature: org.web3j.crypto.Sign.SignatureData): String {
        val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
        val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
        val vBytes = signature.v
        val v = if (vBytes != null && vBytes.isNotEmpty()) {
            vBytes[0].toInt() and 0xff
        } else {
            27
        }
        val vHex = String.format("%02x", v)
        return "0x$rHex$sHex$vHex"
    }

    private fun splitAndPackSig(signature: org.web3j.crypto.Sign.SignatureData): String {
        val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
        val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
        val vBytes = signature.v as ByteArray
        val originalV = if (vBytes.isNotEmpty()) {
            vBytes[0].toInt() and 0xff
        } else {
            throw IllegalArgumentException("Signature v is empty")
        }
        val originalVHex = String.format("%02x", originalV)
        val sigString = "0x$rHex$sHex$originalVHex"
        val sigV = sigString.substring(sigString.length - 2).toInt(16)
        val adjustedV = when (sigV) {
            0, 1 -> sigV + 31
            27, 28 -> sigV + 4
            else -> throw IllegalArgumentException("Invalid signature v value: $sigV")
        }
        val modifiedSigString = sigString.substring(0, sigString.length - 2) + String.format("%02x", adjustedV)
        val rHexStr = modifiedSigString.substring(2, 66)
        val sHexStr = modifiedSigString.substring(66, 130)
        val vHexStr = modifiedSigString.substring(130, 132)
        val rBigInt = BigInteger(rHexStr, 16)
        val sBigInt = BigInteger(sHexStr, 16)
        val vInt = vHexStr.toInt(16)
        val rEncoded = EthereumUtils.encodeUint256(rBigInt)
        val sEncoded = EthereumUtils.encodeUint256(sBigInt)
        val vEncoded = String.format("%02x", vInt)

        return "0x$rEncoded$sEncoded$vEncoded"
    }

    private suspend fun executeManually(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction
    ): Result<String> {
        return try {
            val rpcApi = polygonRpcApi
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
            val fromAddress = credentials.address

            val redeemCallData = safeTx.data.removePrefix("0x")
            val proxyNonceResult = getProxyNonce(proxyAddress, rpcApi)
            val proxyNonce = proxyNonceResult.getOrElse {
                logger.warn("Failed to query proxy nonce, fallback to 0: {}", it.message)
                BigInteger.ZERO
            }
            val safeTxGas = BigInteger.ZERO
            val baseGas = BigInteger.ZERO
            val safeGasPrice = BigInteger.ZERO
            val gasToken = "0x0000000000000000000000000000000000000000"
            val refundReceiver = "0x0000000000000000000000000000000000000000"
            val safeDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeDomain(
                chainId = 137L,
                verifyingContract = proxyAddress
            )
            val safeTxHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeTx(
                to = safeTx.to,
                value = BigInteger.ZERO,
                data = redeemCallData,
                operation = safeTx.operation,
                safeTxGas = safeTxGas,
                baseGas = baseGas,
                gasPrice = safeGasPrice,
                gasToken = gasToken,
                refundReceiver = refundReceiver,
                nonce = proxyNonce
            )
            val safeTxStructuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
                domainSeparator = safeDomainSeparator,
                messageHash = safeTxHash
            )
            val prefix = "\u0019Ethereum Signed Message:\n${safeTxStructuredHash.size}".toByteArray(Charsets.UTF_8)
            val messageWithPrefix = ByteArray(prefix.size + safeTxStructuredHash.size)
            System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
            System.arraycopy(safeTxStructuredHash, 0, messageWithPrefix, prefix.size, safeTxStructuredHash.size)
            val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
            keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
            val hashWithPrefix = ByteArray(keccak256.digestSize)
            keccak256.doFinal(hashWithPrefix, 0)

            val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
            val safeSignature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)
            val vBytes = safeSignature.v as ByteArray
            val vInt = if (vBytes.isNotEmpty()) {
                vBytes[0].toInt() and 0xff
            } else {
                0
            }

            val rHex = org.web3j.utils.Numeric.toHexString(safeSignature.r).removePrefix("0x").padStart(64, '0')
            val sHex = org.web3j.utils.Numeric.toHexString(safeSignature.s).removePrefix("0x").padStart(64, '0')
            val vHex = String.format("%064x", vInt)
            val safeSignatureHex = rHex + sHex + vHex
            val execCallData = buildExecTransactionCallData(safeTx, redeemCallData, safeSignatureHex)
            val nonceResult = getTransactionCount(fromAddress, rpcApi)
            val nonce = nonceResult.getOrElse {
                return Result.failure(Exception("Failed to query nonce: ${it.message}"))
            }
            val gasPriceResult = getGasPrice(rpcApi)
            val gasPrice = gasPriceResult.getOrElse {
                return Result.failure(Exception("Failed to query gas price: ${it.message}"))
            }
            val gasLimit = BigInteger.valueOf(2400000)
            val transaction = buildTransaction(
                privateKey = privateKey,
                from = fromAddress,
                to = proxyAddress,
                data = execCallData,
                nonce = nonce,
                gasLimit = gasLimit,
                gasPrice = gasPrice
            )
            sendTransaction(rpcApi, transaction)
        } catch (e: Exception) {
            logger.error("Failed to deploy safe via Builder Relayer: {}", e.message, e)
            Result.failure(e)
        }
    }

    private fun buildExecTransactionCallData(
        safeTx: SafeTransaction,
        redeemCallData: String,
        safeSignatureHex: String
    ): String {
        val execFunctionSelector =
            EthereumUtils.getFunctionSelector("execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)")

        val encodedTo = EthereumUtils.encodeAddress(safeTx.to)
        val encodedValue = EthereumUtils.encodeUint256(BigInteger.ZERO)

        val dataOffset = BigInteger.valueOf(320L)
        val redeemCallDataHex = redeemCallData.removePrefix("0x")
        val dataLengthBytes = BigInteger.valueOf((redeemCallDataHex.length / 2).toLong())
        val encodedDataOffset = EthereumUtils.encodeUint256(dataOffset)
        val encodedDataLength = EthereumUtils.encodeUint256(dataLengthBytes)
        val dataPaddedLength = ((dataLengthBytes.toInt() + 31) / 32) * 32 * 2
        val encodedData = redeemCallDataHex.padEnd(dataPaddedLength, '0')

        val encodedOperation = EthereumUtils.encodeUint256(BigInteger.valueOf(safeTx.operation.toLong()))
        val encodedSafeTxGas = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val encodedBaseGas = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val encodedGasPrice = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val encodedGasToken = EthereumUtils.encodeAddress("0x0000000000000000000000000000000000000000")
        val encodedRefundReceiver = EthereumUtils.encodeAddress("0x0000000000000000000000000000000000000000")

        val dataPaddedBytes = dataPaddedLength / 2
        val signaturesOffset = BigInteger.valueOf((320 + dataPaddedBytes).toLong())
        val signaturesLength = BigInteger.valueOf(96L)
        val encodedSignaturesOffset = EthereumUtils.encodeUint256(signaturesOffset)
        val encodedSignaturesLength = EthereumUtils.encodeUint256(signaturesLength)
        val encodedSignatures = safeSignatureHex

        return "0x" + execFunctionSelector.removePrefix("0x") +
                encodedTo +
                encodedValue +
                encodedDataOffset +
                encodedDataLength +
                encodedData +
                encodedOperation +
                encodedSafeTxGas +
                encodedBaseGas +
                encodedGasPrice +
                encodedGasToken +
                encodedRefundReceiver +
                encodedSignaturesOffset +
                encodedSignaturesLength +
                encodedSignatures
    }

    private suspend fun getProxyNonce(proxyAddress: String, rpcApi: EthereumRpcApi): Result<BigInteger> {
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

        val hexNonce = rpcResponse.result ?: return Result.failure(Exception("Proxy nonce result is empty"))
        val nonce = EthereumUtils.decodeUint256(hexNonce.asString)
        return Result.success(nonce)
    }

    private suspend fun getTransactionCount(address: String, rpcApi: EthereumRpcApi): Result<BigInteger> {
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

        val hexNonce = rpcResponse.result ?: return Result.failure(Exception("Nonce result is empty"))
        val nonce = EthereumUtils.decodeUint256(hexNonce.asString)
        return Result.success(nonce)
    }

    private suspend fun getGasPrice(rpcApi: EthereumRpcApi): Result<BigInteger> {
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

        val hexGasPrice = rpcResponse.result ?: return Result.failure(Exception("Gas price result is empty"))
        val gasPrice = EthereumUtils.decodeUint256(hexGasPrice.asString)
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

        val txHash = rpcResponse.result ?: return Result.failure(Exception("transaction hash is empty"))
        return Result.success(txHash.asString)
    }

    suspend fun executeBatch(
        privateKey: String,
        proxyAddress: String,
        safeTxs: List<SafeTransaction>
    ): Result<String> {
        return Result.failure(
            UnsupportedOperationException(
                "Gasless batch execution is not supported here; use com.wrbug.polymarketbot.service.common.BlockchainService.redeemPositions() instead"
            )
        )
    }
}



