package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.api.SignedOrderObject
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicLong

/**
 * 
 * - clob-client/src/order-builder/helpers.ts
 */
@Service
class OrderSigningService {

    private val logger = LoggerFactory.getLogger(OrderSigningService::class.java)

    fun getExchangeContract(negRisk: Boolean): String {
        return if (negRisk) NEG_RISK_EXCHANGE_CONTRACT else EXCHANGE_CONTRACT
    }

    fun getSignatureTypeForWalletType(walletType: String?): Int {
        val walletTypeEnum = com.wrbug.polymarketbot.enums.WalletType.fromStringOrDefault(walletType, com.wrbug.polymarketbot.enums.WalletType.SAFE)
        return if (walletTypeEnum == com.wrbug.polymarketbot.enums.WalletType.MAGIC) 1 else 2
    }
    private val EXCHANGE_CONTRACT = PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS
    private val NEG_RISK_EXCHANGE_CONTRACT = PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS
    private val CHAIN_ID = 137L
    private val COLLATERAL_TOKEN_DECIMALS = 6
    private val DEFAULT_TICK_SIZE = "0.01"
    private val DEFAULT_ROUND_CONFIG = RoundConfig(
        price = 2,
        size = 2,
        amount = 4
    )
    private val MIN_PRICE = BigDecimal("0.01")
    private val MAX_PRICE = BigDecimal("0.99")

    data class OrderAmounts(
        val makerAmount: String,
        val takerAmount: String
    )
    
    data class RoundConfig(
        val price: Int,
        val size: Int,
        val amount: Int
    )
    
    fun calculateOrderAmounts(
        side: String,
        size: String,
        price: String,
        roundConfig: RoundConfig = DEFAULT_ROUND_CONFIG
    ): OrderAmounts {
        val sizeDecimal = size.toSafeBigDecimal()
        val priceDecimal = price.toSafeBigDecimal()
        var rawPrice = roundNormal(priceDecimal, roundConfig.price)
        if (rawPrice > MAX_PRICE) {
            logger.warn("价格超出最大限制，已调整: $priceDecimal -> $MAX_PRICE")
            rawPrice = MAX_PRICE
        } else if (rawPrice < MIN_PRICE) {
            logger.warn("价格低于最小限制，已调整: $priceDecimal -> $MIN_PRICE")
            rawPrice = MIN_PRICE
        }

        if (side.uppercase() == "BUY") {
            // BUY: makerAmount = price * size (USDC), takerAmount = size (shares)
            val rawTakerAmt = roundDown(sizeDecimal, 4)

            var rawMakerAmt = rawTakerAmt.multiply(rawPrice)
            if (decimalPlaces(rawMakerAmt) > 2) {
                rawMakerAmt = roundUp(rawMakerAmt, 2 + 4)
                if (decimalPlaces(rawMakerAmt) > 2) {
                    rawMakerAmt = roundDown(rawMakerAmt, 2)
                }
            }
            val makerAmount = parseUnits(rawMakerAmt, COLLATERAL_TOKEN_DECIMALS)
            val takerAmount = parseUnits(rawTakerAmt, COLLATERAL_TOKEN_DECIMALS)

            return OrderAmounts(makerAmount.toString(), takerAmount.toString())
        } else {
            // SELL: makerAmount = size (shares), takerAmount = price * size (USDC)
            val rawMakerAmt = roundDown(sizeDecimal, roundConfig.size)

            var rawTakerAmt = rawMakerAmt.multiply(rawPrice)
            if (decimalPlaces(rawTakerAmt) > roundConfig.amount) {
                rawTakerAmt = roundUp(rawTakerAmt, roundConfig.amount + 4)
                if (decimalPlaces(rawTakerAmt) > roundConfig.amount) {
                    rawTakerAmt = roundDown(rawTakerAmt, roundConfig.amount)
                }
            }
            val makerAmount = parseUnits(rawMakerAmt, COLLATERAL_TOKEN_DECIMALS)
            val takerAmount = parseUnits(rawTakerAmt, COLLATERAL_TOKEN_DECIMALS)

            return OrderAmounts(makerAmount.toString(), takerAmount.toString())
        }
    }
    
    /**
     * 
     * @param tokenId token ID
     */
    fun createAndSignOrder(
        privateKey: String,
        makerAddress: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        signatureType: Int = 2,
        expiration: String = "0",
        timestamp: String = System.currentTimeMillis().toString(),
        metadata: String = PolymarketConstants.ZERO_BYTES32,
        builder: String = PolymarketConstants.ZERO_BYTES32,
        exchangeContract: String? = null
    ): SignedOrderObject {
        try {
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = Credentials.create(privateKeyBigInt.toString(16))
            val signerAddress = credentials.address.lowercase()
            val amounts = calculateOrderAmounts(side, size, price)
            val salt = generateSalt()
            val makerAddressLower = makerAddress.lowercase()
            logger.debug("========== 订单签名前参数 ==========")
            logger.debug("订单方向: $side, 价格: $price, 数量: $size")
            logger.debug("Token ID: $tokenId")
            logger.debug("Maker: ${makerAddressLower.take(10)}...${makerAddressLower.takeLast(6)}")
            logger.debug("Signer: ${signerAddress.take(10)}...${signerAddress.takeLast(6)}")
            logger.debug("Amounts - Maker: ${amounts.makerAmount}, Taker: ${amounts.takerAmount}")
            logger.debug("Salt: $salt, Expiration: $expiration, Timestamp: $timestamp")
            logger.debug("Signature Type: $signatureType, Chain ID: $CHAIN_ID")
            val contract = exchangeContract?.takeIf { it.isNotBlank() } ?: EXCHANGE_CONTRACT
            val signature = signOrder(
                privateKey = privateKey,
                exchangeContract = contract,
                chainId = CHAIN_ID,
                salt = salt,
                maker = makerAddressLower,
                signer = signerAddress,
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                side = side.uppercase(),
                signatureType = signatureType,
                timestamp = timestamp,
                metadata = metadata,
                builder = builder
            )
            return SignedOrderObject(
                salt = salt,
                maker = makerAddressLower,
                signer = signerAddress,
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                side = side.uppercase(),
                signatureType = signatureType,
                timestamp = timestamp,
                metadata = metadata,
                builder = builder,
                signature = signature
            )
        } catch (e: Exception) {
            logger.error("创建并签名订单失败", e)
            throw RuntimeException("创建并签名订单失败: ${e.message}", e)
        }
    }
    
    private fun signOrder(
        privateKey: String,
        exchangeContract: String,
        chainId: Long,
        salt: Long,
        maker: String,
        signer: String,
        tokenId: String,
        makerAmount: String,
        takerAmount: String,
        side: String,
        signatureType: Int,
        timestamp: String,
        metadata: String,
        builder: String
    ): String {
        try {
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = Credentials.create(privateKeyBigInt.toString(16))
            val ecKeyPair = credentials.ecKeyPair
            val domainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeDomain(
                chainId = chainId,
                verifyingContract = exchangeContract.lowercase()
            )
            val orderHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeOrder(
                salt = salt,
                maker = maker,
                signer = signer,
                tokenId = tokenId,
                makerAmount = makerAmount,
                takerAmount = takerAmount,
                side = side,
                signatureType = signatureType,
                timestamp = timestamp,
                metadata = metadata,
                builder = builder
            )
            val structuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
                domainSeparator = domainSeparator,
                messageHash = orderHash
            )
            val signature = org.web3j.crypto.Sign.signMessage(structuredHash, ecKeyPair, false)
            val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
            val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
            val vBytes = signature.v
            val vInt = if (vBytes.isNotEmpty()) vBytes[0].toInt() and 0xff else 0
            val vHex = "%02x".format(vInt)

            return "0x$rHex$sHex$vHex"
        } catch (e: Exception) {
            logger.error("订单签名失败", e)
            throw RuntimeException("订单签名失败: ${e.message}", e)
        }
    }
    
    private val saltSequence = AtomicLong(0)

    private fun generateSalt(): Long {
        val now = System.currentTimeMillis()
        val seq = saltSequence.incrementAndGet() and 0x3FF
        return now * 1000 + seq
    }
    
    private fun parseUnits(value: BigDecimal, decimals: Int): BigInteger {
        val scaledValue = value.setScale(decimals, RoundingMode.DOWN)
        val multiplier = BigInteger.TEN.pow(decimals)
        return scaledValue.multiply(BigDecimal(multiplier)).toBigInteger()
    }
    
    private fun roundNormal(value: BigDecimal, decimals: Int): BigDecimal {
        if (decimalPlaces(value) <= decimals) {
            return value
        }
        return value.setScale(decimals, RoundingMode.HALF_UP)
    }
    
    private fun roundDown(value: BigDecimal, decimals: Int): BigDecimal {
        if (decimalPlaces(value) <= decimals) {
            return value
        }
        return value.setScale(decimals, RoundingMode.DOWN)
    }

    private fun roundUp(value: BigDecimal, decimals: Int): BigDecimal {
        if (decimalPlaces(value) <= decimals) {
            return value
        }
        return value.setScale(decimals, RoundingMode.UP)
    }

    private fun decimalPlaces(value: BigDecimal): Int {
        if (value.scale() <= 0) {
            return 0
        }
        return value.stripTrailingZeros().scale()
    }
}

